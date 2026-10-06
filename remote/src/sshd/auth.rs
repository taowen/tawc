//! Who may log in: either the per-run secret, compared as the SSH
//! username with the `none` method behind a whole-agent guess budget, or
//! a set of public keys (from a code host or pasted).

use std::sync::Mutex;
use std::time::Instant;

use ring::rand::{SecureRandom, SystemRandom};
use russh::keys::PublicKey;
use subtle::ConstantTimeEq;

/// Words in a secret: 3 × 11 bits = 33 bits, sshyeet's default.
pub const SECRET_WORDS: usize = 3;

/// A fresh `word-word-word` secret from the RFC 1751 dictionary (the
/// one relay ids use), e.g. `bold-cook-fern`: short words typed from a
/// phone screen, defended online.
pub fn new_secret() -> std::io::Result<String> {
    let mut b = [0u8; 2 * SECRET_WORDS];
    SystemRandom::new().fill(&mut b).map_err(|_| std::io::Error::other("rng failure"))?;
    // 65536 = 32 × 2048: masking to 11 bits has no bias.
    Ok(b.chunks(2)
        .map(|c| crate::sid::word(u16::from_be_bytes([c[0], c[1]]) as usize))
        .collect::<Vec<_>>()
        .join("-"))
}

/// Could `user` be a secret at all (that many dictionary words)?
/// Anything else, `root` included, costs no guess.
pub fn looks_like_secret(user: &str) -> bool {
    let parts: Vec<&str> = user.split('-').collect();
    parts.len() == SECRET_WORDS && parts.iter().all(|p| crate::sid::is_word(p))
}

pub const GUESSES_PER_MINUTE: f64 = 10.0;
pub const GUESSES_BURST: f64 = 10.0;
/// Wrong secrets per run before secret logins switch off: 300 tries
/// against 2^33 is about one chance in 30 million.
pub const LIFETIME_GUESSES: u32 = 300;

#[derive(Debug, PartialEq, Eq)]
pub enum Verdict {
    Ok,
    /// A guess was spent; stall the reply.
    Wrong,
    /// Not secret-shaped, or secret auth is off: free.
    Refused,
    /// Guess budget empty: refused, right or wrong, until it refills.
    Throttled,
    /// This wrong guess used up the lifetime budget.
    Disabled,
}

struct Inner {
    secret: String,
    tokens: f64,
    refilled: Instant,
    wrong_total: u32,
    disabled: bool,
}

/// Keys from authorized_keys-style text (a code host's `.keys`, or
/// pasted): one per line; blank lines, `#` comments, leading options and
/// unknown key types are skipped.
pub fn parse_authorized_keys(text: &str) -> Vec<PublicKey> {
    const TYPES: &[&str] = &["ssh-", "ecdsa-", "sk-"];
    text.lines()
        .take(1000)
        .filter_map(|line| {
            let line = line.trim();
            if line.starts_with('#') {
                return None;
            }
            // Skip an options prefix (`from="…" ssh-ed25519 …`).
            let start = line
                .match_indices(|c: char| c.is_ascii_alphabetic())
                .map(|(i, _)| i)
                .find(|&i| (i == 0 || line.as_bytes()[i - 1] == b' ') && TYPES.iter().any(|t| line[i..].starts_with(t)))?;
            PublicKey::from_openssh(&line[start..]).ok()
        })
        .take(100)
        .collect()
}

/// Secret mode: the whole check is one critical section, so concurrency
/// buys an attacker nothing: a guess is taken before the comparison and
/// refunded only when right. Key mode: `keys` is the authorized set and
/// the secret is empty (off).
pub struct Policy {
    inner: Mutex<Inner>,
    keys: Vec<PublicKey>,
}

impl Policy {
    pub fn with_keys(keys: Vec<PublicKey>) -> Self {
        Policy { keys, ..Self::new(String::new()) }
    }

    pub fn key_allowed(&self, key: &PublicKey) -> bool {
        self.keys.iter().any(|k| k.key_data() == key.key_data())
    }

    pub fn key_count(&self) -> usize {
        self.keys.len()
    }

    pub fn new(secret: String) -> Self {
        Policy {
            keys: Vec::new(),
            inner: Mutex::new(Inner {
                secret,
                tokens: GUESSES_BURST,
                refilled: Instant::now(),
                wrong_total: 0,
                disabled: false,
            }),
        }
    }

    pub fn secret(&self) -> String {
        self.inner.lock().unwrap().secret.clone()
    }

    /// New secret (new identity): budget and lockout start over.
    pub fn set_secret(&self, s: String) {
        let mut i = self.inner.lock().unwrap();
        i.secret = s;
        i.wrong_total = 0;
        i.disabled = false;
        i.tokens = GUESSES_BURST;
    }

    pub fn disabled(&self) -> bool {
        self.inner.lock().unwrap().disabled
    }

    pub fn try_secret(&self, user: &str) -> Verdict {
        self.try_secret_at(user, Instant::now())
    }

    fn try_secret_at(&self, user: &str, now: Instant) -> Verdict {
        let mut i = self.inner.lock().unwrap();
        if i.disabled || i.secret.is_empty() || !looks_like_secret(user) {
            return Verdict::Refused;
        }
        let dt = now.saturating_duration_since(i.refilled).as_secs_f64();
        i.tokens = (i.tokens + dt * GUESSES_PER_MINUTE / 60.0).min(GUESSES_BURST);
        i.refilled = now;
        if i.tokens < 1.0 {
            return Verdict::Throttled;
        }
        i.tokens -= 1.0;
        if bool::from(user.as_bytes().ct_eq(i.secret.as_bytes())) {
            i.tokens += 1.0;
            return Verdict::Ok;
        }
        i.wrong_total += 1;
        if i.wrong_total >= LIFETIME_GUESSES {
            i.disabled = true;
            return Verdict::Disabled;
        }
        Verdict::Wrong
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn secret_shape() {
        for _ in 0..200 {
            let s = new_secret().unwrap();
            assert!(looks_like_secret(&s), "{s}");
            assert!(s.len() <= 14, "{s}");
        }
        assert_ne!(new_secret().unwrap(), new_secret().unwrap());
    }

    #[test]
    fn looks_like() {
        assert!(looks_like_secret("bold-cook-fern"));
        assert!(looks_like_secret("a-a-a"));
        for bad in ["root", "bold-cook", "bold-cook-fern-fist", "Bold-cook-fern", "bold-cook-ferns", "bold--fern", "bold-cook-417"] {
            assert!(!looks_like_secret(bad), "{bad}");
        }
    }

    #[test]
    fn bucket_and_refund() {
        let p = Policy::new("bold-cook-fern".into());
        let t0 = Instant::now();
        // Right answers are free, non-secret shapes too.
        for _ in 0..50 {
            assert_eq!(p.try_secret_at("bold-cook-fern", t0), Verdict::Ok);
            assert_eq!(p.try_secret_at("root", t0), Verdict::Refused);
        }
        for _ in 0..10 {
            assert_eq!(p.try_secret_at("bold-cook-fist", t0), Verdict::Wrong);
        }
        // Budget empty: even the right secret is refused until refill.
        assert_eq!(p.try_secret_at("bold-cook-fern", t0), Verdict::Throttled);
        assert_eq!(p.try_secret_at("bold-cook-fern", t0 + Duration::from_secs(7)), Verdict::Ok);
        assert_eq!(p.try_secret_at("bold-cook-fist", t0 + Duration::from_secs(7)), Verdict::Wrong);
        assert_eq!(p.try_secret_at("bold-cook-fist", t0 + Duration::from_secs(7)), Verdict::Throttled);
    }

    #[test]
    fn lifetime_cap() {
        let p = Policy::new("bold-cook-fern".into());
        let mut t = Instant::now();
        for n in 1..=LIFETIME_GUESSES {
            t += Duration::from_secs(6);
            let v = p.try_secret_at("bold-cook-fist", t);
            if n < LIFETIME_GUESSES {
                assert_eq!(v, Verdict::Wrong, "guess {n}");
            } else {
                assert_eq!(v, Verdict::Disabled);
            }
        }
        t += Duration::from_secs(600);
        assert_eq!(p.try_secret_at("bold-cook-fern", t), Verdict::Refused);
        assert!(p.disabled());
        p.set_secret("new-odd-ten".into());
        assert_eq!(p.try_secret_at("new-odd-ten", t), Verdict::Ok);
    }

    #[test]
    fn authorized_keys_parsing() {
        let a = crate::hostkey::HostKey::from_seed(&[1; 32]).unwrap().openssh;
        let b = crate::hostkey::HostKey::from_seed(&[2; 32]).unwrap().openssh;
        let c = crate::hostkey::HostKey::from_seed(&[3; 32]).unwrap().openssh;
        let text = format!("# mine\n\n{a} me@laptop\r\nfrom=\"10.0.0.1\",no-pty {b}\nnot a key\nssh-ed25519 AAAAbroken\n");
        let keys = parse_authorized_keys(&text);
        assert_eq!(keys.len(), 2);
        let p = Policy::with_keys(keys);
        let pk = |s: &str| PublicKey::from_openssh(s).unwrap();
        assert!(p.key_allowed(&pk(&a)));
        // Comments don't matter, only the key.
        assert!(p.key_allowed(&pk(&format!("{b} other-comment"))));
        assert!(!p.key_allowed(&pk(&c)));
        // Key mode has no secret.
        assert_eq!(p.try_secret("bold-cook-fern"), Verdict::Refused);
    }

    #[test]
    fn concurrent_guesses_share_one_budget() {
        let p = std::sync::Arc::new(Policy::new("bold-cook-fern".into()));
        let wrong = std::sync::Arc::new(std::sync::atomic::AtomicU32::new(0));
        let hs: Vec<_> = (0..8)
            .map(|_| {
                let (p, wrong) = (p.clone(), wrong.clone());
                std::thread::spawn(move || {
                    for _ in 0..20 {
                        if p.try_secret("bold-cook-fist") == Verdict::Wrong {
                            wrong.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                        }
                    }
                })
            })
            .collect();
        for h in hs {
            h.join().unwrap();
        }
        // Burst of 10, plus at most a sliver of refill while the test ran.
        assert!((10..=11).contains(&wrong.load(std::sync::atomic::Ordering::SeqCst)));
    }
}
