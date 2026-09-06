"""Read-only guest probe: reports capacity and entry count, never file names."""
from __future__ import annotations

import argparse
import json
from urllib.parse import unquote, urlsplit


def probe(url: str) -> None:
    from impacket import smb, smb3structs as smb2
    from impacket.smbconnection import SMBConnection

    endpoint = urlsplit(url)
    if endpoint.scheme != "smb" or not endpoint.hostname or endpoint.username or endpoint.password:
        raise ValueError("Use smb://host/share with no embedded credentials")
    segments = [unquote(part) for part in endpoint.path.strip("/").split("/")]
    if not segments or not segments[0] or any(part in (".", "..") for part in segments):
        raise ValueError("A share is required and traversal paths are not allowed")
    share = segments[0]
    path = "\\".join(segments[1:])
    client = SMBConnection(endpoint.hostname, endpoint.hostname, sess_port=endpoint.port or 445, timeout=8)
    summary = {"connected": False, "authenticated": False, "entryCount": None, "totalBytes": None, "freeBytes": None}
    try:
        summary["connected"] = True
        client.login("", "")
        summary["authenticated"] = True
        entries = client.listPath(share, (path + "\\" if path else "") + "*")
        summary["entryCount"] = sum(entry.get_longname() not in (".", "..") for entry in entries)
        tree = client.connectTree(share)
        file_id = client.openFile(share if isinstance(tree, str) else tree, path,
                                  desiredAccess=smb2.FILE_READ_ATTRIBUTES,
                                  shareMode=smb2.FILE_SHARE_READ | smb2.FILE_SHARE_WRITE | smb2.FILE_SHARE_DELETE,
                                  creationOption=smb2.FILE_DIRECTORY_FILE,
                                  creationDisposition=smb2.FILE_OPEN)
        try:
            raw = client.getSMBServer().queryInfo(tree, file_id, infoType=smb2.SMB2_0_INFO_FILESYSTEM,
                                                fileInfoClass=7)
            capacity = smb.SMBFileFsFullSizeInformation(raw)
            unit_bytes = capacity["SectorsPerAllocationUnit"] * capacity["BytesPerSector"]
            summary["totalBytes"] = capacity["TotalAllocationUnits"] * unit_bytes
            summary["freeBytes"] = capacity["CallerAvailableAllocationUnits"] * unit_bytes
        finally:
            client.closeFile(tree, file_id)
            client.disconnectTree(tree)
    except Exception as error:
        summary["errorType"] = type(error).__name__
        if hasattr(error, "getErrorCode"):
            summary["statusCode"] = hex(error.getErrorCode())
    finally:
        client.close()
    print(json.dumps(summary))


if __name__ == "__main__":
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("url")
    options = arguments.parse_args()
    try:
        probe(options.url)
    except Exception as error:
        print(json.dumps({"connected": False, "errorType": type(error).__name__}))
