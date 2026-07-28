package eu.wohlben.qits.projects.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A pseudo-terminal over the libc calls, reached through {@code java.lang.foreign}: the master side
 * this process reads and writes, plus the slave device path a child is handed as its stdio.
 *
 * <p><b>Why this exists rather than pty4j.</b> The sign-in terminal needs a real TTY because that is
 * the only way {@code git push} will prompt — it opens {@code /dev/tty}, so a pipe gets a silent
 * non-interactive failure instead of a username prompt. pty4j gave us that, at the price of JNA plus
 * a set of per-platform native libraries that it unpacks from its own jar into a temp directory at
 * runtime. Both halves are exactly what a GraalVM native image cannot resolve at build time: the JNA
 * dispatch library is loaded by name, the {@code Native}/{@code Structure} machinery is reflective,
 * and a library extracted at runtime was never seen by the image builder at all. The failure mode is
 * the bad one — the JVM suite stays green and the binary throws on the first sign-in. Six libc entry
 * points reached directly cost the builder nothing and remove the dependency outright.
 * {@code qits-workspace-daemon}'s {@code ForeignPty} is the same call, made for the same reason;
 * this is that class with the daemon's {@code Pty} seam dropped, because the daemon also runs chat
 * sessions on pipes and every session here is a terminal by definition.
 *
 * <p><b>Native image: the descriptors are declared, not inferred.</b> GraalVM 25 registers
 * <em>zero</em> downcall stubs by itself. Hoisting the {@link FunctionDescriptor}s into
 * {@code static final} constants does not change that — measured both ways, the builder reports
 * "0 downcalls and 0 upcalls registered for foreign access" either way — and the image build then
 * fails while parsing {@link #open} with {@code unexpected input could not be handled: linkToNative}.
 * Every descriptor below therefore has a matching entry in
 * {@code META-INF/native-image/eu.wohlben.qits/qits-projects-domain/reachability-metadata.json},
 * and the two have to move together: change a signature here and the binary either fails to build
 * or throws {@code MissingForeignRegistrationError} on the first sign-in, while the JVM suite goes
 * on passing. The constants stay hoisted anyway, because one place to read the ABI off is what
 * makes keeping that file honest possible at all.
 *
 * <p><b>Linux only</b>, deliberately. {@link #TIOCSWINSZ} and the {@code struct winsize} layout are
 * this kernel's. The sign-in terminal is a host-side operation on the machine that owns the bare
 * origins, and that host is Linux; a different platform should get its own implementation rather
 * than a portability layer here.
 *
 * <p>Reads and writes go through {@code read}/{@code write} on the master fd because the JDK offers
 * no public way to wrap an arbitrary file descriptor in a stream, and reaching
 * {@code FileDescriptor.fd} reflectively is exactly the kind of thing a native image makes you
 * register — which would put back the cost this class exists to remove.
 */
final class ForeignPty implements AutoCloseable {

  // --- libc constants (linux) -----------------------------------------------------------------

  private static final int O_RDWR = 0x0002;
  private static final int O_NOCTTY = 0x0100;

  /** {@code ioctl} request that sets the window size and raises SIGWINCH. */
  private static final long TIOCSWINSZ = 0x5414L;

  /** {@code struct winsize} is four unsigned shorts: rows, cols, xpixel, ypixel. */
  private static final int WINSIZE_BYTES = 8;

  private static final int PTSNAME_MAX = 256;

  // --- downcall handles -----------------------------------------------------------------------

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();

  private static final FunctionDescriptor OPEN_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor GRANTPT_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor UNLOCKPT_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor PTSNAME_R_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final FunctionDescriptor IOCTL_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
  private static final FunctionDescriptor READ_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final FunctionDescriptor WRITE_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final FunctionDescriptor CLOSE_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

  private static final MethodHandle OPEN = downcall("open", OPEN_DESC);
  private static final MethodHandle GRANTPT = downcall("grantpt", GRANTPT_DESC);
  private static final MethodHandle UNLOCKPT = downcall("unlockpt", UNLOCKPT_DESC);
  private static final MethodHandle PTSNAME_R = downcall("ptsname_r", PTSNAME_R_DESC);

  /**
   * {@code ioctl} is variadic ({@code int ioctl(int, unsigned long, ...)}), so the third argument
   * has to be declared as the first variadic one or the ABI's register assignment is wrong.
   */
  private static final MethodHandle IOCTL =
      LINKER.downcallHandle(
          LIBC.find("ioctl").orElseThrow(() -> new UnsatisfiedLinkError("ioctl")),
          IOCTL_DESC,
          Linker.Option.firstVariadicArg(2));

  private static final MethodHandle READ = downcall("read", READ_DESC);
  private static final MethodHandle WRITE = downcall("write", WRITE_DESC);
  private static final MethodHandle CLOSE = downcall("close", CLOSE_DESC);

  private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(
        LIBC.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(symbol)), descriptor);
  }

  // --- instance -------------------------------------------------------------------------------

  private final int masterFd;
  private final String slavePath;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final InputStream in = new MasterInput();
  private final OutputStream out = new MasterOutput();

  private ForeignPty(int masterFd, String slavePath) {
    this.masterFd = masterFd;
    this.slavePath = slavePath;
  }

  /**
   * Allocate a PTY and set its initial size.
   *
   * @throws IOException if any of the four setup calls fails — the caller turns that into a failed
   *     spawn, which is the same outcome it had when {@code PtyProcessBuilder.start()} threw
   */
  static ForeignPty open(int cols, int rows) throws IOException {
    int fd;
    String slave;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment path = arena.allocateFrom("/dev/ptmx");
      // O_NOCTTY: opening the master must never make it this process's controlling terminal.
      fd = (int) OPEN.invokeExact(path, O_RDWR | O_NOCTTY);
      if (fd < 0) {
        throw new IOException("open(/dev/ptmx) failed");
      }
      int granted = (int) GRANTPT.invokeExact(fd);
      if (granted != 0) {
        closeFd(fd);
        throw new IOException("grantpt failed");
      }
      int unlocked = (int) UNLOCKPT.invokeExact(fd);
      if (unlocked != 0) {
        closeFd(fd);
        throw new IOException("unlockpt failed");
      }
      MemorySegment buffer = arena.allocate(PTSNAME_MAX);
      // ptsname_r over ptsname: the latter returns a pointer into a static buffer, which two
      // concurrent sign-ins would race.
      int named = (int) PTSNAME_R.invokeExact(fd, buffer, (long) PTSNAME_MAX);
      if (named != 0) {
        closeFd(fd);
        throw new IOException("ptsname_r failed");
      }
      slave = buffer.getString(0);
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("Could not allocate a pseudo-terminal: " + t, t);
    }
    ForeignPty pty = new ForeignPty(fd, slave);
    pty.resize(cols, rows);
    return pty;
  }

  /** Output produced by whatever holds the slave end — the child's merged stdout and stderr. */
  InputStream in() {
    return in;
  }

  /** Input delivered to the slave end — what the user typed into the web terminal. */
  OutputStream out() {
    return out;
  }

  /**
   * The slave device path ({@code /dev/pts/N}), for handing to a child as its stdio. Valid until
   * {@link #close()}.
   */
  String slavePath() {
    return slavePath;
  }

  /**
   * Set the terminal window size, which makes the kernel deliver {@code SIGWINCH} to the foreground
   * process group. Best-effort: a closed or invalid PTY is ignored rather than throwing, because a
   * resize racing the push's exit is ordinary and must not surface as an error to the browser.
   */
  void resize(int cols, int rows) {
    if (closed.get() || cols <= 0 || rows <= 0) {
      return;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment winsize = arena.allocate(WINSIZE_BYTES);
      // struct winsize field order is rows first, then cols; the pixel fields stay zero, which is
      // what every terminal emulator that does not do sixel reports.
      winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
      winsize.set(ValueLayout.JAVA_SHORT, 2, (short) cols);
      winsize.set(ValueLayout.JAVA_SHORT, 4, (short) 0);
      winsize.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
      int unused = (int) IOCTL.invokeExact(masterFd, TIOCSWINSZ, winsize);
    } catch (Throwable ignored) {
      // Best-effort by contract: a resize arriving after the process exited is ordinary.
    }
  }

  /**
   * Close the master. This is a terminal hangup: the kernel delivers {@code SIGHUP} to the
   * foreground process group of the session that owns the slave, which is what a closed terminal
   * window does and what {@code git} treats as "stop asking, the user is gone".
   */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      closeFd(masterFd);
    }
  }

  private static void closeFd(int fd) {
    try {
      int unused = (int) CLOSE.invokeExact(fd);
    } catch (Throwable ignored) {
      // Nothing useful to do with a failed close of a descriptor we are discarding.
    }
  }

  /** Reads from the master. EOF (0) and EIO — what a PTY reports once its slave is gone — end it. */
  private final class MasterInput extends InputStream {

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n <= 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (closed.get()) {
        return -1;
      }
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment buffer = arena.allocate(length);
        long n = (long) READ.invokeExact(masterFd, buffer, (long) length);
        if (n <= 0) {
          // A PTY master reports EIO rather than EOF when the last slave closes; both mean the
          // conversation is over, and the reader thread treats -1 as the process having gone.
          return -1;
        }
        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, destination, offset, (int) n);
        return (int) n;
      } catch (Throwable t) {
        throw new IOException("PTY read failed", t);
      }
    }

    @Override
    public void close() {
      ForeignPty.this.close();
    }
  }

  /** Writes to the master — the user's keystrokes reaching the child's terminal. */
  private final class MasterOutput extends OutputStream {

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
      if (closed.get()) {
        throw new IOException("PTY is closed");
      }
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment buffer = arena.allocate(length);
        MemorySegment.copy(source, offset, buffer, ValueLayout.JAVA_BYTE, 0, length);
        int written = 0;
        while (written < length) {
          // A short write is normal once the slave's input buffer fills; loop rather than lose
          // keystrokes.
          long n =
              (long) WRITE.invokeExact(masterFd, buffer.asSlice(written), (long) (length - written));
          if (n <= 0) {
            throw new IOException("PTY write failed");
          }
          written += (int) n;
        }
      } catch (IOException e) {
        throw e;
      } catch (Throwable t) {
        throw new IOException("PTY write failed", t);
      }
    }

    @Override
    public void close() {
      ForeignPty.this.close();
    }
  }
}
