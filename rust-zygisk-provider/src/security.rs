use std::mem::size_of;
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixStream;

pub fn require_root_peer(stream: &UnixStream) -> Result<(), String> {
    let credentials = peer_credentials(stream)?;
    if credentials.uid != 0 {
        return Err(format!(
            "control socket rejected uid={} pid={}",
            credentials.uid, credentials.pid
        ));
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeerCredentials {
    pub pid: i32,
    pub uid: u32,
    pub gid: u32,
}

pub fn peer_credentials(stream: &UnixStream) -> Result<PeerCredentials, String> {
    let mut credentials = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut length = size_of::<libc::ucred>() as libc::socklen_t;

    // SAFETY: the output buffer points to a valid ucred value and length describes it exactly.
    let result = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut credentials as *mut libc::ucred).cast(),
            &mut length,
        )
    };
    if result != 0 {
        return Err(std::io::Error::last_os_error().to_string());
    }
    if length as usize != size_of::<libc::ucred>() {
        return Err(format!("unexpected SO_PEERCRED size: {length}"));
    }

    Ok(PeerCredentials {
        pid: credentials.pid,
        uid: credentials.uid,
        gid: credentials.gid,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixStream;

    #[test]
    fn reads_credentials_from_socket_pair() {
        let (left, _right) = UnixStream::pair().unwrap();
        let credentials = peer_credentials(&left).unwrap();
        assert_eq!(credentials.uid, unsafe { libc::geteuid() });
        assert!(credentials.pid > 0);
    }
}
