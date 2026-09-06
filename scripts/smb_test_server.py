"""A loopback-only SMB 2 test fixture; never exposes a Windows user folder."""
from __future__ import annotations

import argparse
import hashlib
import io
import logging
import os
from pathlib import Path
from uuid import uuid4

USERNAME = "android-test"
PASSWORD = "SendToSMB-test-only!"
SHARE = "TESTSHARE"


def smoke_test(port: int) -> None:
    from impacket.smbconnection import SMBConnection

    client = SMBConnection("127.0.0.1", "127.0.0.1", sess_port=port)
    client.login(USERNAME, PASSWORD)
    payload = b"SendToSMB isolated SMB integration test\n" * 1024
    folder = "host-smoke-" + uuid4().hex[:10]
    try:
        client.createDirectory(SHARE, folder)
        client.putFile(SHARE, folder + "/upload.bin", io.BytesIO(payload).read)
        downloaded = io.BytesIO()
        client.getFile(SHARE, folder + "/upload.bin", downloaded.write)
        assert hashlib.sha256(downloaded.getvalue()).digest() == hashlib.sha256(payload).digest()
        client.rename(SHARE, folder + "/upload.bin", folder + "/renamed.bin")
        assert "renamed.bin" in [entry.get_longname() for entry in client.listPath(SHARE, folder + "/*")]
        client.deleteFile(SHARE, folder + "/renamed.bin")
        client.deleteDirectory(SHARE, folder)
        print("PASS: authenticate, mkdir, upload, download + SHA-256, rename, list, delete", flush=True)
    finally:
        client.close()


def serve(root: Path, port: int) -> None:
    from impacket import smbserver
    from impacket.ntlm import compute_lmhash, compute_nthash

    # Disable impacket authentication logging: test fixtures need no credential capture.
    logging.disable(logging.CRITICAL)
    root.mkdir(parents=True, exist_ok=True)
    fixtures = {
        "Read me.txt": b"Welcome to the isolated SendToSMB test share.\r\n",
        "Documents/中文文件名.txt": "手机与平板文件管理测试\n".encode("utf-8"),
        "Documents/Empty file.txt": b"",
        "Downloads/sample.bin": bytes(range(256)) * 4096,
        ".hidden-example": b"A hidden file test fixture.\n",
    }
    for relative, content in fixtures.items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            target.write_bytes(content)
    server = smbserver.SimpleSMBServer(listenAddress="127.0.0.1", listenPort=port)
    server.addShare(SHARE, str(root.resolve()), "SendToSMB isolated test fixture", readOnly="no")
    server.setSMB2Support(True)
    server.addCredential(USERNAME, 0, compute_lmhash(PASSWORD), compute_nthash(PASSWORD))
    install_full_size_information_workaround(server)
    if os.name == "nt":
        install_windows_rename_workaround(server)
    print(f"READY smb://127.0.0.1:{port}/{SHARE}", flush=True)
    server.start()


def install_full_size_information_workaround(server) -> None:
    """Return all 32 bytes for SMB2 filesystem class 7 (Impacket confuses it with EA)."""
    from impacket import smb3structs as smb2, smb

    def query_info(connection_id, smb_server, packet):
        request = smb2.SMB2QueryInfo(packet["Data"])
        result = original(connection_id, smb_server, packet)
        if request["InfoType"] == smb2.SMB2_0_INFO_FILESYSTEM and request["FileInfoClass"] == 7 and result[2] == 0:
            data = smb.SMBFileFsFullSizeInformation().getData()
            result[0][0]["Buffer"] = data
            result[0][0]["OutputBufferLength"] = len(data)
        return result

    original = server.getServer().hookSmb2Command(smb2.SMB2_QUERY_INFO, query_info)


def install_windows_rename_workaround(server) -> None:
    """Impacket uses os.open without FILE_SHARE_DELETE; close/reopen around rename."""
    from impacket import smb3structs as smb2, smbserver

    def set_info(connection_id, smb_server, packet):
        request = smb2.SMB2SetInfo(packet["Data"])
        if request["InfoType"] != smb2.SMB2_0_INFO_FILE or request["FileInfoClass"] != smb2.SMB2_FILE_RENAME_INFO:
            return original(connection_id, smb_server, packet)
        connection = smb_server.getConnectionData(connection_id)
        file_id = request["FileID"].getData()
        if file_id == b"\xff" * 16 and "SMB2_CREATE" in connection["LastRequest"]:
            file_id = connection["LastRequest"]["SMB2_CREATE"]["FileID"]
        opened = connection["OpenedFiles"].get(file_id)
        descriptor = opened["FileHandle"] if opened else smbserver.VOID_FILE_DESCRIPTOR
        if descriptor in (smbserver.VOID_FILE_DESCRIPTOR, smbserver.PIPE_FILE_DESCRIPTOR):
            return original(connection_id, smb_server, packet)
        position = os.lseek(descriptor, 0, os.SEEK_CUR)
        os.close(descriptor)
        opened["FileHandle"] = smbserver.VOID_FILE_DESCRIPTOR
        smb_server.setConnectionData(connection_id, connection)
        result = original(connection_id, smb_server, packet)
        connection = smb_server.getConnectionData(connection_id)
        opened = connection["OpenedFiles"][file_id]
        opened["FileHandle"] = os.open(opened["FileName"], os.O_RDWR | os.O_BINARY)
        os.lseek(opened["FileHandle"], position, os.SEEK_SET)
        smb_server.setConnectionData(connection_id, connection)
        return result

    original = server.getServer().hookSmb2Command(smb2.SMB2_SET_INFO, set_info)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=1445)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1] / ".testenv" / "share")
    parser.add_argument("--smoke-test", action="store_true")
    options = parser.parse_args()
    if options.smoke_test:
        smoke_test(options.port)
    else:
        serve(options.root, options.port)
