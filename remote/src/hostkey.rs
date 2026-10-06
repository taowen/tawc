//! The ed25519 host key: kept in a file (one per device, so ssh's
//! known_hosts can check it), or throwaway.

use std::io::{self, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::Ed25519KeyPair;
use russh::keys::ssh_key::private::Ed25519Keypair;
use russh::keys::ssh_key::{HashAlg, LineEnding};
use russh::keys::PrivateKey;

pub struct HostKey {
    pair: Ed25519KeyPair,
    pub private: PrivateKey,
    /// SSH wire encoding of the public key.
    pub wire: Vec<u8>,
    /// `ssh-ed25519 AAAA…`, authorized_keys format without comment.
    pub openssh: String,
    /// `SHA256:…`
    pub fingerprint: String,
}

fn put_string(out: &mut Vec<u8>, s: &[u8]) {
    out.extend_from_slice(&(s.len() as u32).to_be_bytes());
    out.extend_from_slice(s);
}

impl HostKey {
    pub fn generate() -> io::Result<HostKey> {
        let mut seed = [0u8; 32];
        SystemRandom::new()
            .fill(&mut seed)
            .map_err(|_| io::Error::other("rng failure"))?;
        Self::from_seed(&seed)
    }

    /// The key at `path` (OpenSSH private key format), created there on
    /// first use. An unreadable or foreign file is replaced: a missing
    /// remote login beats a stuck one, and ssh will say the key changed.
    pub fn load_or_create(path: &Path) -> io::Result<HostKey> {
        if let Some(k) = std::fs::read_to_string(path).ok().and_then(|t| Self::from_openssh(&t)) {
            return Ok(k);
        }
        let k = Self::generate()?;
        let text = k.private.to_openssh(LineEnding::LF).map_err(io::Error::other)?;
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let tmp = path.with_extension("tmp");
        let _ = std::fs::remove_file(&tmp);
        let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&tmp)?;
        f.write_all(text.as_bytes())?;
        f.sync_all()?;
        std::fs::rename(&tmp, path)?;
        Ok(k)
    }

    fn from_openssh(text: &str) -> Option<HostKey> {
        let private = PrivateKey::from_openssh(text).ok()?;
        let seed = private.key_data().ed25519()?.private.to_bytes();
        Self::from_seed(&seed).ok()
    }

    pub fn from_seed(seed: &[u8; 32]) -> io::Result<HostKey> {
        let pair = Ed25519KeyPair::from_seed_unchecked(seed).map_err(|_| io::Error::other("bad key seed"))?;
        let private = PrivateKey::from(Ed25519Keypair::from_seed(seed));
        let public = private.public_key();
        let wire = public.to_bytes().map_err(io::Error::other)?;
        let openssh = public.to_openssh().map_err(io::Error::other)?.trim().to_string();
        let fingerprint = public.fingerprint(HashAlg::Sha256).to_string();
        Ok(HostKey { pair, private, wire, openssh, fingerprint })
    }

    /// SSH signature wire encoding (`string "ssh-ed25519" || string sig`)
    /// over `msg`: what Go's `ssh.Marshal(ssh.Signature)` produces.
    pub fn sign_ssh(&self, msg: &[u8]) -> Vec<u8> {
        let sig = self.pair.sign(msg);
        let mut out = Vec::with_capacity(83);
        put_string(&mut out, b"ssh-ed25519");
        put_string(&mut out, sig.as_ref());
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persisted_key_is_reused() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("tawc-hostkey-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join("remote/host_key");
        let a = HostKey::load_or_create(&path).unwrap();
        let b = HostKey::load_or_create(&path).unwrap();
        assert_eq!(a.openssh, b.openssh);
        assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        // ssh-keygen can read it (standard format).
        assert!(std::fs::read_to_string(&path).unwrap().starts_with("-----BEGIN OPENSSH PRIVATE KEY-----"));
        // A corrupt file is replaced.
        std::fs::write(&path, "junk").unwrap();
        let c = HostKey::load_or_create(&path).unwrap();
        assert_ne!(c.openssh, a.openssh);
        assert_eq!(HostKey::load_or_create(&path).unwrap().openssh, c.openssh);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn encodings_agree() {
        let k = HostKey::from_seed(&[1u8; 32]).unwrap();
        assert!(k.openssh.starts_with("ssh-ed25519 AAAA"));
        assert_eq!(k.openssh.split(' ').count(), 2);
        assert!(k.fingerprint.starts_with("SHA256:"));
        // wire = string "ssh-ed25519" || string pub(32)
        assert_eq!(k.wire.len(), 4 + 11 + 4 + 32);
        let pubkey = &k.wire[19..];
        let sig = k.sign_ssh(b"hello");
        assert_eq!(&sig[..15], b"\0\0\0\x0bssh-ed25519");
        let raw = &sig[19..];
        ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, pubkey)
            .verify(b"hello", raw)
            .unwrap();
        assert_eq!(
            data_encoding::BASE64.encode(&k.wire),
            k.openssh.split(' ').nth(1).unwrap()
        );
    }
}
