package me.nsmr.vocabsurgery.runtime

import java.net.{InetAddress, ServerSocket}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** A fake TensorFold clone for runtime and CLI tests. */
object RuntimeFixtures:
  def freePort(): Int = Using.resource(ServerSocket(0, 1, InetAddress.getLoopbackAddress))(_.getLocalPort)

  def executable(path: Path, text: String): Path =
    Files.createDirectories(path.getParent)
    Files.writeString(path, text)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
    path

  /** A clone whose `tensorfold serve` answers GET /v1/models on its --port, or exits at once when `fails`. */
  def fakeClone(root: Path, fails: Boolean = false): Path =
    val clone = Files.createDirectories(root.resolve("clone"))
    val server = "import http.server, json, sys\n" +
      "class H(http.server.BaseHTTPRequestHandler):\n" +
      "    def do_GET(self):\n" +
      "        body = json.dumps({'data': [{'id': sys.argv[2]}]}).encode()\n" +
      "        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.end_headers(); self.wfile.write(body)\n" +
      "    def do_POST(self):\n" +
      "        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))\n" +
      "        drafts = body.get('draft', True)\n" +
      "        events = [{'choices': [{'delta': {'content': 'ok'}}]}, {'choices': [{'delta': {}, 'finish_reason': 'stop'}]},\n" +
      "                  {'choices': [], 'usage': {'prompt_tokens': 3, 'completion_tokens': 2}}]\n" +
      "        data = ''.join('data: ' + json.dumps(e) + '\\n\\n' for e in events) + 'data: [DONE]\\n\\n'\n" +
      "        self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers(); self.wfile.write(data.encode())\n" +
      "        stats = 'rounds=1 accepted=1/1' if drafts else 'rounds=2 accepted=0/0'\n" +
      "        print(f'[tensorfold] done req-x prompt=3 cached=0 thinking=False tokens=2 sha=0 finish=stop tok/s=10.0 ttft=0.01s prefill=0.01s {stats}', flush=True)\n" +
      "    def log_message(self, *args): pass\n" +
      "http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()\n"
    Files.writeString(clone.resolve("server.py"), server)
    executable(clone.resolve(".venv/bin/tensorfold"), "#!/bin/sh\n" +
      (if fails then "echo 'CUDA out of memory'; exit 3\n" else "") +
      "port=''; name=''\nwhile [ $# -gt 0 ]; do case \"$1\" in --port) port=$2; shift;; --name) name=$2; shift;; esac; shift; done\n" +
      s"echo \"serving $$name on $$port\"\nexec python3 ${clone.resolve("server.py")} \"$$port\" \"$$name\"\n")
    def git(args: String*): Unit =
      val process = ProcessBuilder((Vector("git", "-C", clone.toString, "-c", "user.name=t", "-c", "user.email=t@example.invalid") ++ args).asJava)
        .redirectErrorStream(true).start()
      process.getInputStream.readAllBytes()
      require(process.waitFor() == 0, "git failed")
    git("init", "-q")
    git("commit", "-q", "--allow-empty", "-m", "fake")
    clone
