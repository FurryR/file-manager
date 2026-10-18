//! Connection identity for the daemon socket.
//!
//! The socket name is *derived*, never chosen: `file-daemon-<hex sha256(pubkey
//! || 0 || id)>`. Both the app and the daemon compute it from the same Ed25519
//! public key and target id, which gives a stable, reattachable address without
//! a fixed secret name. Because the name is derivable by anything that reads
//! the public key, it is not a credential — every accepted connection must
//! still pass the nonce/signature handshake below.

use std::io;

use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use sha2::{Digest, Sha256};

/// Domain separator prefixing every signed handshake message.
const AUTH_CONTEXT: &[u8] = b"file-daemon-auth-v1\0";

/// Parse a lowercase/uppercase hex string into bytes.
pub fn parse_hex(value: &str) -> Option<Vec<u8>> {
    if value.len() % 2 != 0 {
        return None;
    }
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(value.len() / 2);
    for pair in bytes.chunks_exact(2) {
        let high = (pair[0] as char).to_digit(16)?;
        let low = (pair[1] as char).to_digit(16)?;
        out.push(((high << 4) | low) as u8);
    }
    Some(out)
}

fn to_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

/// Stable abstract socket name for a daemon identity. Must match the Kotlin
/// `DaemonIdentity.socketName`.
pub fn derive_socket_name(public_key: &[u8], id: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(public_key);
    hasher.update([0u8]);
    hasher.update(id.as_bytes());
    format!("file-daemon-{}", to_hex(&hasher.finalize()))
}

/// A fresh 32-byte challenge nonce from the OS CSPRNG.
pub fn random_nonce() -> io::Result<[u8; 32]> {
    let mut nonce = [0u8; 32];
    getrandom::fill(&mut nonce).map_err(|e| io::Error::other(format!("getrandom failed: {e}")))?;
    Ok(nonce)
}

/// The exact message a client must sign: context, the daemon's socket name and
/// the per-connection nonce. Binding the name prevents a signature harvested
/// from one daemon being replayed against another.
pub fn auth_message(socket_name: &str, nonce: &[u8]) -> Vec<u8> {
    let mut message = Vec::with_capacity(AUTH_CONTEXT.len() + socket_name.len() + 1 + nonce.len());
    message.extend_from_slice(AUTH_CONTEXT);
    message.extend_from_slice(socket_name.as_bytes());
    message.push(0);
    message.extend_from_slice(nonce);
    message
}

/// Verify a client's signature with the public key the daemon was launched with.
pub fn verify(public_key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    let Ok(key_bytes) = <[u8; 32]>::try_from(public_key) else {
        return false;
    };
    let Ok(verifying_key) = VerifyingKey::from_bytes(&key_bytes) else {
        return false;
    };
    let Ok(signature_bytes) = <[u8; 64]>::try_from(signature) else {
        return false;
    };
    let signature = Signature::from_bytes(&signature_bytes);
    verifying_key.verify(message, &signature).is_ok()
}
