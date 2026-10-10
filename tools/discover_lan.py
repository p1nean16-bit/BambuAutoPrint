"""只读发现局域网拓竹设备；不登录、不发送打印或修改配置命令。"""
import json
import socket
import time


def discover(seconds=8):
    devices = {}
    group, port = "239.255.255.250", 2021
    probe = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:2021\r\n"
             'MAN: "ssdp:discover"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n').encode("ascii")
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as client:
        client.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        client.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        try:
            client.bind(("", port))
            client.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                              socket.inet_aton(group) + socket.inet_aton("0.0.0.0"))
        except OSError:
            # 端口占用时仍可用临时端口接收发现响应。
            client.close()
            return {"devices": [], "error": "设备发现端口不可用，请手动提供打印机 IP 或重试"}
        client.settimeout(0.4)
        client.sendto(probe, (group, port))
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                data, address = client.recvfrom(16384)
            except socket.timeout:
                continue
            message = data.decode("utf-8", errors="replace")
            if "bambu" not in message.lower():
                continue
            headers = {}
            for line in message.splitlines()[1:]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    headers[key.strip().lower()] = value.strip()
            serial = headers.get("usn", "")
            devices[address[0]] = {
                "ip": address[0], "serial": serial,
                "name": headers.get("devname.bambu.com", ""),
                "model": headers.get("devmodel.bambu.com", ""),
                "firmware": headers.get("devversion.bambu.com", ""),
            }
    return {"devices": list(devices.values())}


if __name__ == "__main__":
    try:
        result = discover()
    except OSError as error:
        result = {"devices": [], "error": str(error)}
    print(json.dumps(result, ensure_ascii=False))
