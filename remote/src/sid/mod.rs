//! Session ids: a pure function of the host key, computed the same way
//! the relay does, so the agent can check the id the relay assigns.
//!
//! Current relays (sshyeet with RFC 1751 words):
//!
//! ```text
//! H     = SHA-256("sshyeet id v3\0" || ssh-wire(pubkey))
//! short = W[bits(H, 0:11)] "-" W[bits(H, 11:22)] "-" ...   (n words from H[0:16])
//! long  = short "-" base32(H[16:26])   (16 chars, lowercase, no padding)
//! ```
//!
//! `n` is the agent's choice (`id_words` in the hello; 2 by default). A
//! relay from before that change ignores `id_words` and assigns the
//! [`legacy`] three-word id; the agent accepts either, as long as it is
//! derived from its own key.

mod legacy_words;
mod rfc1751;

use sha2::{Digest, Sha256};

pub const WORD_BITS: u32 = 11;
pub const MAX_WORDS: usize = 8;
pub const DEFAULT_ID_WORDS: usize = 2;

const HASH_DOMAIN: &[u8] = b"sshyeet id v3\0";
const WORD_BYTES: usize = 16;
const BIND_OFF: usize = 16;
const BIND_BYTES: usize = 10;

fn base32() -> data_encoding::Encoding {
    let mut spec = data_encoding::Specification::new();
    spec.symbols.push_str("abcdefghijklmnopqrstuvwxyz234567");
    spec.encoding().expect("valid base32 spec")
}

fn key_hash(domain: &[u8], key_wire: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(domain);
    h.update(key_wire);
    h.finalize().into()
}

/// The `i`-th 11-bit big-endian field of `h`.
fn word_index(h: &[u8], i: usize) -> usize {
    (i * WORD_BITS as usize..(i + 1) * WORD_BITS as usize)
        .fold(0, |v, b| v << 1 | ((h[b / 8] >> (7 - b % 8)) & 1) as usize)
}

/// Dictionary word at `i` (mod 2048).
pub fn word(i: usize) -> &'static str {
    rfc1751::WORDS[i & (rfc1751::WORDS.len() - 1)]
}

pub fn is_word(w: &str) -> bool {
    static SET: std::sync::OnceLock<std::collections::HashSet<&'static str>> = std::sync::OnceLock::new();
    SET.get_or_init(|| rfc1751::WORDS.iter().copied().collect()).contains(w)
}

/// Short (routing, typing) id of `n` words (clamped to 1..=8) for a host
/// key in SSH wire encoding.
pub fn derive(key_wire: &[u8], n: usize) -> String {
    let h = key_hash(HASH_DOMAIN, key_wire);
    (0..n.clamp(1, MAX_WORDS)).map(|i| word(word_index(&h[..WORD_BYTES], i))).collect::<Vec<_>>().join("-")
}

/// Self-certifying long id.
pub fn derive_long(key_wire: &[u8], n: usize) -> String {
    let h = key_hash(HASH_DOMAIN, key_wire);
    format!("{}-{}", derive(key_wire, n), base32().encode(&h[BIND_OFF..BIND_OFF + BIND_BYTES]))
}

/// Ids of relays from before RFC 1751: `ADJ-ADJ-NOUN` from
/// `SHA-256("sshyeet id v2\0" || key)`, long form `-base32(H[3..13])`.
pub mod legacy {
    use super::legacy_words::{ADJECTIVES, NOUNS};

    const HASH_DOMAIN: &[u8] = b"sshyeet id v2\0";

    pub fn derive(key_wire: &[u8]) -> String {
        let h = super::key_hash(HASH_DOMAIN, key_wire);
        format!("{}-{}-{}", ADJECTIVES[h[0] as usize], ADJECTIVES[h[1] as usize], NOUNS[h[2] as usize])
    }

    pub fn derive_long(key_wire: &[u8]) -> String {
        let h = super::key_hash(HASH_DOMAIN, key_wire);
        format!("{}-{}", derive(key_wire), super::base32().encode(&h[3..13]))
    }
}

/// Which derivation produced a relay's id.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scheme {
    /// RFC 1751 words, as many as asked for.
    Words(usize),
    /// A relay that predates `id_words`.
    Legacy,
}

/// The scheme under which `id` (short form, as a relay's `ready` carries
/// it) names this key when `n` words were asked for; None if it names
/// some other key.
pub fn identify(id: &str, key_wire: &[u8], n: usize) -> Option<Scheme> {
    if id == derive(key_wire, n) {
        Some(Scheme::Words(n))
    } else if id == legacy::derive(key_wire) {
        Some(Scheme::Legacy)
    } else {
        None
    }
}

/// The long id matching `scheme`.
pub fn long_for(scheme: Scheme, key_wire: &[u8]) -> String {
    match scheme {
        Scheme::Words(n) => derive_long(key_wire, n),
        Scheme::Legacy => legacy::derive_long(key_wire),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dictionary() {
        assert_eq!(rfc1751::WORDS.len(), 2048);
        let mut seen = std::collections::HashSet::new();
        for w in rfc1751::WORDS {
            assert!((1..=4).contains(&w.len()) && w.bytes().all(|c| c.is_ascii_lowercase()), "{w}");
            assert!(seen.insert(w), "duplicate {w}");
        }
        assert_eq!((word(0), word(2047), word(2048)), ("a", "yoke", "a"));
        assert!(is_word("yoke") && is_word("root") && !is_word("roots") && !is_word("Yoke"));
    }

    #[test]
    fn legacy_lists_are_distinct_lowercase() {
        let mut seen = std::collections::HashSet::new();
        for w in legacy_words::ADJECTIVES.iter().chain(legacy_words::NOUNS.iter()) {
            assert!(w.chars().all(|c| c.is_ascii_lowercase()), "{w}");
            assert!(seen.insert(*w), "duplicate {w}");
        }
    }

    #[test]
    fn word_index_bits() {
        // Upstream's vector: alternating all-ones / all-zeros fields.
        let h = [0xff, 0xe0, 0x03, 0xff, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        let got: Vec<usize> = (0..4).map(|i| word_index(&h, i)).collect();
        assert_eq!(got, [2047, 0, 2047, 0]);
    }

    // Ed25519 key with public bytes 0x00..0x1f. Expected values computed
    // independently (Python hashlib + the word lists).
    fn vector_key() -> Vec<u8> {
        let mut wire = Vec::new();
        wire.extend_from_slice(&11u32.to_be_bytes());
        wire.extend_from_slice(b"ssh-ed25519");
        wire.extend_from_slice(&32u32.to_be_bytes());
        wire.extend((0u8..32).collect::<Vec<_>>());
        wire
    }

    #[test]
    fn vectors() {
        let k = vector_key();
        assert_eq!(derive(&k, 1), "none");
        assert_eq!(derive(&k, 2), "none-rays");
        assert_eq!(derive(&k, 3), "none-rays-frey");
        assert_eq!(derive(&k, 8), "none-rays-frey-po-oust-lola-feed-news");
        assert_eq!(derive(&k, 0), "none");
        assert_eq!(derive(&k, 9), derive(&k, 8));
        assert_eq!(derive_long(&k, 2), "none-rays-hrcx6z2hk2si3szp");
        assert_eq!(legacy::derive(&k), "subtle-plush-skunk");
        assert_eq!(legacy::derive_long(&k), "subtle-plush-skunk-v7qxwzgpj6h6nbo3");
    }

    #[test]
    fn identify_either_scheme() {
        let k = vector_key();
        assert_eq!(identify("none-rays", &k, 2), Some(Scheme::Words(2)));
        assert_eq!(identify("none-rays-frey", &k, 3), Some(Scheme::Words(3)));
        // The wrong count for what was asked, a long form, or another key's id.
        assert_eq!(identify("none-rays-frey", &k, 2), None);
        assert_eq!(identify("none-rays-hrcx6z2hk2si3szp", &k, 2), None);
        assert_eq!(identify("subtle-plush-skunk", &k, 2), Some(Scheme::Legacy));
        let mut other = k.clone();
        *other.last_mut().unwrap() ^= 1;
        assert_eq!(identify("none-rays", &other, 2), None);
        assert_eq!(identify("subtle-plush-skunk", &other, 2), None);
    }
}
