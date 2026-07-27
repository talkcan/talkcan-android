"""DAP proxy between OMP and fwcd kotlin-debug-adapter 0.4.4 for on-device
Android (ART/JDWP) attach.

fwcd 0.4.4 (lsp4j#229) never completes its `configurationDone` future, and its
`initialize` response goes missing once this runs behind the wrapper's adb/PATH
layer, so a spec-compliant DAP client (OMP) that waits on either response wedges
with "The operation timed out." The proxy therefore:

  * synthesizes the `initialize` response to the client immediately (using
    fwcd's real capabilities body), and
  * synthesizes the `configurationDone` response,

while passing every other frame (initialized/output/thread events, attach /
threads / setBreakpoints responses) through verbatim. It also papers over two
fwcd NPEs: `linesStartAt1`/`columnsStartAt1` are defaulted on `initialize`
(OMP omits them) and a pathless `Source.name` is backfilled from the basename.
"""
import json, os, sys, subprocess, threading, atexit, signal

# fwcd 0.4.4's real `initialize` capabilities body (captured from a direct run).
INIT_BODY = json.loads('{"supportsConfigurationDoneRequest": true, "exceptionBreakpointFilters": [{"filter": "C", "label": "Caught Exceptions", "default": false}, {"filter": "U", "label": "Uncaught Exceptions", "default": false}], "supportsCompletionsRequest": true, "supportsExceptionInfoRequest": true}')

FORWARD = "tcp:37099"


def _cleanup():
    # The wrapper `exec`s this proxy, so this process owns teardown of the adb
    # forward. </dev/null so adb cannot touch the (now closed) DAP stdin.
    try:
        subprocess.run(["adb", "forward", "--remove", FORWARD],
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


atexit.register(_cleanup)


def _on_signal(signum, frame):
    _cleanup()
    os._exit(128 + signum)


signal.signal(signal.SIGTERM, _on_signal)
signal.signal(signal.SIGINT, _on_signal)

init_req_seq = None
conf_req_seq = None
req_lock = threading.Lock()


def parse_dap_stream(stream):
    while True:
        headers = {}
        headers_raw = b""
        line = stream.readline()
        if not line:
            break
        headers_raw += line
        while line and line.strip():
            k, _, v = line.decode('utf-8', errors='replace').partition(":")
            headers[k.strip().lower()] = v.strip()
            line = stream.readline()
            if line:
                headers_raw += line
        n = int(headers.get("content-length", 0))
        body = stream.read(n)
        if len(body) < n:
            break
        yield headers_raw, body


def forward_stdin(proc):
    global init_req_seq, conf_req_seq
    try:
        for headers_raw, body_bytes in parse_dap_stream(sys.stdin.buffer):
            msg = None
            try:
                msg = json.loads(body_bytes)
            except Exception:
                msg = None
            if isinstance(msg, dict):
                if msg.get("type") == "request" and msg.get("command") == "configurationDone":
                    with req_lock:
                        conf_req_seq = msg.get("seq")
                # fwcd 0.4.4 dereferences the optional DAP fields
                # linesStartAt1/columnsStartAt1 as non-null in `initialize`
                # ("getLinesStartAt1(...) must not be null"). OMP omits them,
                # so default them to true (the DAP 1-based convention). Record
                # the request seq: main() synthesizes the initialize response.
                if msg.get("type") == "request" and msg.get("command") == "initialize":
                    margs = msg.setdefault("arguments", {})
                    margs.setdefault("linesStartAt1", True)
                    margs.setdefault("columnsStartAt1", True)
                    with req_lock:
                        init_req_seq = msg.get("seq")
                # fwcd 0.4.4 NPEs ("getName(...) must not be null") when a
                # Source carries a path but no name; backfill from the basename.
                margs = msg.get("arguments") if isinstance(msg.get("arguments"), dict) else None
                src = margs.get("source") if margs else None
                if isinstance(src, dict) and src.get("path") and not src.get("name"):
                    src["name"] = os.path.basename(src["path"])
                # Body changed: re-serialize with a correct Content-Length.
                body_bytes = json.dumps(msg).encode('utf-8')
                headers_raw = ("Content-Length: %d\r\n\r\n" % len(body_bytes)).encode('utf-8')
            proc.stdin.write(headers_raw)
            proc.stdin.write(body_bytes)
            proc.stdin.flush()
    except Exception:
        pass
    finally:
        proc.stdin.close()


def main():
    global init_req_seq, conf_req_seq
    proc = subprocess.Popen(
        ["kotlin-debug-adapter"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=sys.stderr.buffer
    )

    threading.Thread(target=forward_stdin, args=(proc,), daemon=True).start()

    client_seq = 1

    def send_to_client(msg):
        nonlocal client_seq
        msg["seq"] = client_seq
        client_seq += 1
        data = json.dumps(msg).encode('utf-8')
        sys.stdout.buffer.write(b"Content-Length: %d\r\n\r\n" % len(data) + data)
        sys.stdout.buffer.flush()

    try:
        for headers_raw, body_bytes in parse_dap_stream(proc.stdout):
            # Synthesize the responses fwcd withholds, ahead of the frame that
            # releases them, preserving DAP order (initialize response before
            # the initialized event).
            with req_lock:
                if init_req_seq is not None:
                    send_to_client({
                        "type": "response",
                        "request_seq": init_req_seq,
                        "success": True,
                        "command": "initialize",
                        "body": INIT_BODY
                    })
                    init_req_seq = None
                if conf_req_seq is not None:
                    send_to_client({
                        "type": "response",
                        "request_seq": conf_req_seq,
                        "success": True,
                        "command": "configurationDone"
                    })
                    conf_req_seq = None

            try:
                msg = json.loads(body_bytes)
                # fwcd's own initialize response: the client already got the
                # synthesized one above; drop it to avoid a duplicate.
                if msg.get("type") == "response" and msg.get("command") == "initialize":
                    continue
                # Everything else (initialized event, output, thread events,
                # attach/threads/setBreakpoints responses) passes through as-is.
                send_to_client(msg)
            except Exception:
                sys.stdout.buffer.write(headers_raw)
                sys.stdout.buffer.write(body_bytes)
                sys.stdout.buffer.flush()

    except Exception:
        pass
    finally:
        proc.kill()


if __name__ == "__main__":
    main()
