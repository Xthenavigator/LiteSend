import re
import sys

HANDSHAKE_NAMES = {
    1: 'ClientHello', 2: 'ServerHello', 11: 'Certificate', 12: 'ServerKeyExchange',
    14: 'ServerHelloDone', 16: 'ClientKeyExchange', 20: 'Finished',
    4: 'NewSessionTicket', 13: 'CertificateRequest', 15: 'CertificateVerify',
}
ALERT_NAMES = {0: 'close_notify', 10: 'unexpected_message', 20: 'bad_record_mac',
               40: 'handshake_failure', 42: 'bad_certificate', 80: 'internal_error',
               90: 'user_canceled', 100: 'no_renegotiation', 112: 'unrecognized_name'}


def blocks(text):
    out = {}
    current = None
    for raw in text.splitlines():
        line = raw.strip()
        m = re.match(r'=== (CLIENT->SERVER|SERVER->CLIENT) \((\d+) bytes\) ===', line)
        if m:
            current = m.group(1)
            out[current] = ''
            continue
        if current and re.fullmatch(r'[0-9a-fA-F]+', line or '') and len(line) % 2 == 0:
            out[current] += line
        elif current and line and not re.fullmatch(r'[0-9a-fA-F]+', line):
            current = None
    return {name: bytes.fromhex(hexstr) for name, hexstr in out.items()}


def describe_records(name, data):
    print('--- %s: %d bytes' % (name, len(data)))
    offset = 0
    while offset + 5 <= len(data):
        ctype = data[offset]
        version = data[offset + 1:offset + 3].hex()
        length = int.from_bytes(data[offset + 3:offset + 5], 'big')
        payload = data[offset + 5:offset + 5 + length]
        info = 'record type=%d ver=%s len=%d' % (ctype, version, length)
        if ctype == 22 and len(payload) >= 4:
            htype = payload[0]
            hlen = int.from_bytes(payload[1:4], 'big')
            info += ' handshake=%s(%d) len=%d' % (HANDSHAKE_NAMES.get(htype, htype), htype, hlen)
            if htype == 1 and len(payload) >= 4 + 2 + 32:
                p = 4 + 2 + 32
                sid_len = payload[p]
                p += 1 + sid_len
                cs_len = int.from_bytes(payload[p:p + 2], 'big')
                suites = [payload[p + 2 + i:p + 4 + i].hex() for i in range(0, cs_len, 2)]
                info += ' suites=%s' % ','.join(suites)
                p += 2 + cs_len
                comp_len = payload[p]
                p += 1 + comp_len
                ext_len = int.from_bytes(payload[p:p + 2], 'big')
                p += 2
                exts = []
                end = p + ext_len
                while p + 4 <= end:
                    et = int.from_bytes(payload[p:p + 2], 'big')
                    el = int.from_bytes(payload[p + 2:p + 4], 'big')
                    val = payload[p + 4:p + 4 + el]
                    if et == 10 and len(val) >= 2:
                        groups = [int.from_bytes(val[2 + i:4 + i], 'big') for i in range(0, len(val) - 2, 2)]
                        exts.append('groups=%s' % ','.join('0x%04x' % g for g in groups))
                    elif et == 13 and len(val) >= 2:
                        algs = ['%02x%02x' % (val[2 + i], val[3 + i]) for i in range(0, len(val) - 2, 2)]
                        exts.append('sigalgs=%s' % ','.join(algs))
                    elif et == 11 and len(val) >= 1:
                        exts.append('pointformats=%s' % val[1:].hex())
                    else:
                        exts.append('ext(0x%04x,len=%d)' % (et, el))
                    p += 4 + el
                info += ' exts=[%s]' % ' '.join(exts)
            if htype == 16 and len(payload) >= 5:
                pub_len = payload[4]
                info += ' ecdhe_pub_len=%d pub=%s' % (pub_len, payload[5:5 + pub_len].hex())
            if htype == 12 and len(payload) >= 4:
                info += ' params_head=%s' % payload[4:20].hex()
            if htype == 2 and len(payload) >= 4:
                sid_len = payload[4 + 1 + 2 + 32] if len(payload) > 4 + 35 else 0
                info += ' server_sessionid_len=%d' % sid_len
        elif ctype == 21 and len(payload) >= 2:
            info += ' ALERT level=%d desc=%s(%d)' % (payload[0], ALERT_NAMES.get(payload[1], '?'), payload[1])
        print(info)
        offset += 5 + length
    if offset != len(data):
        print('trailing %d bytes: %s' % (len(data) - offset, data[offset:].hex()))


def main():
    with open(sys.argv[1], encoding='utf-8', errors='replace') as f:
        text = f.read()
    for name, data in blocks(text).items():
        describe_records(name, data)


if __name__ == '__main__':
    main()
