use jni::{
    JNIEnv,
    objects::{JByteArray, JClass},
    sys::{jboolean, jbyteArray},
};
use opaque_ke::{
    CipherSuite, ClientLogin, ClientLoginFinishParameters, ClientRegistration,
    ClientRegistrationFinishParameters, CredentialResponse, RegistrationResponse, Ristretto255,
    TripleDh,
};
use rand::{RngCore, rngs::OsRng};
use ring::aead::{Aad, CHACHA20_POLY1305, LessSafeKey, Nonce, UnboundKey};
use zeroize::{Zeroize, Zeroizing};

struct Profile;

impl CipherSuite for Profile {
    type OprfCs = Ristretto255;
    type KeyExchange = TripleDh<Ristretto255, sha2::Sha512>;
    type Ksf = argon2::Argon2<'static>;
}

fn hardening() -> argon2::Argon2<'static> {
    argon2::Argon2::new(
        argon2::Algorithm::Argon2id,
        argon2::Version::V0x13,
        argon2::Params::new(19 * 1024, 2, 1, None).expect("valid Olymp profile"),
    )
}

type Bytes = Result<Vec<u8>, ()>;

fn result(env: &mut JNIEnv, bytes: Bytes, error: &str) -> jbyteArray {
    if let Ok(mut bytes) = bytes {
        let array = env.byte_array_from_slice(&bytes);
        bytes.zeroize();
        if let Ok(array) = array {
            return array.into_raw();
        }
    }
    let _ = env.throw_new("java/security/GeneralSecurityException", error);
    std::ptr::null_mut()
}

fn read(env: &JNIEnv, bytes: &JByteArray) -> Result<Zeroizing<Vec<u8>>, ()> {
    env.convert_byte_array(bytes)
        .map(Zeroizing::new)
        .map_err(|_| ())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_thia_gestalter_core_Crypto_opaqueStart(
    mut env: JNIEnv,
    _: JClass,
    password: JByteArray,
    register: jboolean,
) -> jbyteArray {
    let started = (|| {
        let password = read(&env, &password)?;
        if register != 0 {
            let start =
                ClientRegistration::<Profile>::start(&mut OsRng, &password).map_err(|_| ())?;
            Ok(start_bytes(
                &start.message.serialize(),
                &start.state.serialize(),
            ))
        } else {
            let start = ClientLogin::<Profile>::start(&mut OsRng, &password).map_err(|_| ())?;
            Ok(start_bytes(
                &start.message.serialize(),
                &start.state.serialize(),
            ))
        }
    })();
    result(&mut env, started, "Unable to start sign-in.")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_thia_gestalter_core_Crypto_opaqueFinish(
    mut env: JNIEnv,
    _: JClass,
    password: JByteArray,
    response: JByteArray,
    state: JByteArray,
    register: jboolean,
) -> jbyteArray {
    let finished = (|| {
        let password = read(&env, &password)?;
        let response = read(&env, &response)?;
        let state = read(&env, &state)?;
        let ksf = hardening();
        if register != 0 {
            let finish = ClientRegistration::<Profile>::deserialize(&state)
                .map_err(|_| ())?
                .finish(
                    &mut OsRng,
                    &password,
                    RegistrationResponse::deserialize(&response).map_err(|_| ())?,
                    ClientRegistrationFinishParameters::new(Default::default(), Some(&ksf)),
                )
                .map_err(|_| ())?;
            Ok([&finish.message.serialize()[..], &finish.export_key[..]].concat())
        } else {
            let finish = ClientLogin::<Profile>::deserialize(&state)
                .map_err(|_| ())?
                .finish(
                    &mut OsRng,
                    &password,
                    CredentialResponse::deserialize(&response).map_err(|_| ())?,
                    ClientLoginFinishParameters::new(None, Default::default(), Some(&ksf)),
                )
                .map_err(|_| ())?;
            Ok([&finish.message.serialize()[..], &finish.export_key[..]].concat())
        }
    })();
    result(&mut env, finished, "Unable to unlock this Olymp account.")
}

fn start_bytes(message: &[u8], state: &[u8]) -> Vec<u8> {
    [&(message.len() as u16).to_be_bytes()[..], message, state].concat()
}

fn quarter_round(s: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    s[a] = s[a].wrapping_add(s[b]);
    s[d] = (s[d] ^ s[a]).rotate_left(16);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] = (s[b] ^ s[c]).rotate_left(12);
    s[a] = s[a].wrapping_add(s[b]);
    s[d] = (s[d] ^ s[a]).rotate_left(8);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] = (s[b] ^ s[c]).rotate_left(7);
}

// HChaCha20 derives the subkey. ring implements the IETF AEAD and tag check.
fn hchacha(key: &[u8], nonce: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut s = Zeroizing::new([0u32; 16]);
    for (word, bytes) in s.iter_mut().zip(
        b"expand 32-byte k"
            .chunks_exact(4)
            .chain(key.chunks_exact(4))
            .chain(nonce.chunks_exact(4)),
    ) {
        *word = u32::from_le_bytes(bytes.try_into().unwrap());
    }
    for _ in 0..10 {
        for (a, b, c, d) in [
            (0, 4, 8, 12),
            (1, 5, 9, 13),
            (2, 6, 10, 14),
            (3, 7, 11, 15),
            (0, 5, 10, 15),
            (1, 6, 11, 12),
            (2, 7, 8, 13),
            (3, 4, 9, 14),
        ] {
            quarter_round(&mut s, a, b, c, d);
        }
    }
    let mut out = Zeroizing::new([0u8; 32]);
    for (word, bytes) in s[..4].iter().chain(&s[12..]).zip(out.chunks_exact_mut(4)) {
        bytes.copy_from_slice(&word.to_le_bytes());
    }
    out
}

fn xchacha(key: &[u8], aad: &[u8], data: &[u8], seal: bool) -> Bytes {
    let mut nonce = [0u8; 24];
    let mut body = if seal {
        OsRng.fill_bytes(&mut nonce);
        Zeroizing::new(data.to_vec())
    } else {
        if data.len() < 40 {
            return Err(());
        }
        nonce.copy_from_slice(&data[..24]);
        Zeroizing::new(data[24..].to_vec())
    };
    if key.len() != 32 {
        return Err(());
    }
    let subkey = hchacha(key, &nonce[..16]);
    let aead = LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, &*subkey).map_err(|_| ())?);
    let mut ietf_nonce = [0u8; 12];
    ietf_nonce[4..].copy_from_slice(&nonce[16..]);
    let ietf_nonce = Nonce::assume_unique_for_key(ietf_nonce);
    if seal {
        aead.seal_in_place_append_tag(ietf_nonce, Aad::from(aad), &mut *body)
            .map_err(|_| ())?;
        Ok([&nonce[..], &body[..]].concat())
    } else {
        let plain = aead
            .open_in_place(ietf_nonce, Aad::from(aad), &mut body)
            .map_err(|_| ())?;
        Ok(plain.to_vec())
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_thia_gestalter_core_Crypto_content(
    mut env: JNIEnv,
    _: JClass,
    key: JByteArray,
    aad: JByteArray,
    data: JByteArray,
    seal: jboolean,
) -> jbyteArray {
    let checked = (|| {
        xchacha(
            &read(&env, &key)?,
            &read(&env, &aad)?,
            &read(&env, &data)?,
            seal != 0,
        )
    })();
    result(&mut env, checked, "Olymp data failed authentication.")
}
