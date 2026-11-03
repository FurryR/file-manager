// pty_hangup_spin.c -- regression harness for the "hung-up pty burns a core" bug.
//
// Why this exists
// ---------------
// `agent/src/terminal.rs` spawns the `agent-input` thread, whose body is a
// `poll(2)` loop with a 50 ms timeout. That timeout is the loop's *only*
// throttle, and a hung-up pty defeats it: once the master end is gone, poll()
// returns immediately with POLLIN|POLLHUP|POLLERR, reads return EOF, and a loop
// that can only distinguish "event" / "no event" spins at ~1.6M iterations/s --
// one full core, almost entirely in `sys` time, for as long as the process
// lives. That is how an agent orphaned into init(1) kept 6 cores busy for days
// (see system_cpu_runaway_diagnosis.md).
//
// Usage
// -----
//   cc -O2 -o pty_hangup_spin scripts/pty_hangup_spin.c
//   ./pty_hangup_spin 0        # crossterm semantics: POLLHUP is "no event" -> spins
//   ./pty_hangup_spin 1        # fixed: POLLHUP|ERR|NVAL means EOF -> exits
//   ./pty_hangup_spin 2        # fixed: tcgetpgrp()==EIO (agent's hung_up()) -> exits
//   ./pty_hangup_spin 0 6 3    # control: the app keeps a master dup for 3 s;
//                              # nothing burns CPU until that last ref goes away
//
// Expected results (arm64, 8 core): mode 0 spends ~5 s of CPU in 6 s wall
// (~1.6M loops/s, sys >> user); modes 1 and 2 exit after one iteration with
// ~1 ms of CPU. Any build of the loop that reproduces mode 0 is broken.
//
// Environment knobs used to rule out the "safety net" explanations:
//   SIGHUP_DFL=1  leave SIGHUP at its default action -> the process is killed by
//                 the kernel at hang-up, i.e. SIGHUP only helps when it lands.
//   SH_LIKE=1     also fork a session leader that stays alive (the container
//                 `-sh`) and keep the reader in its own process group.
//   PTMX=<path>   open the master from a private devpts instance.
//   UMOUNT=<dir>  lazily unmount that devpts instead of closing the master.

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define MODE_CROSSTERM 0
#define MODE_HUP_OK    1
#define MODE_TCGETPGRP 2

static int report_fd = -1;

static double mono(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

static double cpu_sec(void) {
    struct rusage r;
    getrusage(RUSAGE_SELF, &r);
    return (double)r.ru_utime.tv_sec + (double)r.ru_utime.tv_usec / 1e6 +
           (double)r.ru_stime.tv_sec + (double)r.ru_stime.tv_usec / 1e6;
}

static void say(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void say(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (n > 0) {
        ssize_t w = write(report_fd, buf, (size_t)n);
        (void)w;
    }
}

/* Everything the reader can observe about a dead terminal, in one shot. */
static void diagnose(int fd) {
    char path[128] = "?";
    char lnk[64];
    snprintf(lnk, sizeof lnk, "/proc/self/fd/%d", fd);
    ssize_t l = readlink(lnk, path, sizeof path - 1);
    if (l > 0) path[l] = 0;

    struct stat st;
    int fst = fstat(fd, &st);

    errno = 0;
    int pgrp = tcgetpgrp(fd);
    int e_pgrp = errno;

    struct pollfd p = { .fd = fd, .events = POLLIN, .revents = 0 };
    errno = 0;
    int pr = poll(&p, 1, 0);
    int e_poll = errno;

    char c;
    errno = 0;
    ssize_t rd = read(fd, &c, 1);
    int e_read = errno;

    errno = 0;
    ssize_t wr = write(fd, "x", 1);
    int e_write = errno;

    struct winsize ws;
    errno = 0;
    int wsr = ioctl(fd, TIOCGWINSZ, &ws);
    int e_ws = errno;

    say("  fd%d -> %s\n", fd, path);
    say("  fstat      ret=%d mode=%#o rdev=%#llx\n", fst, fst == 0 ? (unsigned)st.st_mode : 0u,
        fst == 0 ? (unsigned long long)st.st_rdev : 0ULL);
    say("  isatty     ret=%d\n", isatty(fd));
    say("  tcgetpgrp  ret=%d errno=%d(%s)\n", pgrp, e_pgrp, strerror(e_pgrp));
    say("  poll(to=0) ret=%d revents=%#x%s errno=%d(%s)\n", pr, p.revents,
        (p.revents & POLLIN) ? " (POLLIN set!)" : "", e_poll, strerror(e_poll));
    say("  read(1B)   ret=%zd errno=%d(%s)\n", rd, e_read, strerror(e_read));
    say("  write(1B)  ret=%zd errno=%d(%s)\n", wr, e_write, strerror(e_write));
    say("  TIOCGWINSZ ret=%d errno=%d(%s)\n", wsr, e_ws, strerror(e_ws));
}

static void child_loop(int mode, int secs) {
    if (setsid() < 0) _exit(3);
    ioctl(0, TIOCSCTTY, 0);

    struct termios t;
    if (tcgetattr(0, &t) == 0) { /* what crossterm's enable_raw_mode does */
        cfmakeraw(&t);
        t.c_iflag &= ~(IXON | IXOFF);
        t.c_iflag |= IUTF8;
        tcsetattr(0, TCSANOW, &t);
    }

    /* Re-create the field topology: a long-lived session leader (the container
     * `-sh`, pid 16399 on the incident machine) that never exits, with the
     * reader in its own process group. */
    if (getenv("SH_LIKE")) {
        pid_t w = fork();
        if (w > 0) {
            close(report_fd);
            signal(SIGHUP, SIG_IGN);
            tcsetpgrp(0, getpgrp());
            int st;
            waitpid(w, &st, 0);
            _exit(0);
        }
        setpgid(0, 0);
        signal(SIGTTIN, SIG_IGN); /* a background pgrp read would stop us */
        signal(SIGTTOU, SIG_IGN);
    }

    if (getenv("SIGHUP_DFL")) signal(SIGHUP, SIG_DFL);
    else signal(SIGHUP, SIG_IGN); /* keep the process alive to observe the loop */

    int hung = 0;
    long iters_sec = 0, iters_total = 0, hup_retry = 0, eof = 0, rderr = 0;
    unsigned seen_revents = 0;
    double t0 = mono(), lastcpu = cpu_sec();
    int last_sec = -1;

    while (mono() - t0 < secs) {
        struct pollfd p = { .fd = 0, .events = POLLIN, .revents = 0 };
        int r = poll(&p, 1, 50); /* the project's Duration::from_millis(50) */
        iters_sec++;
        iters_total++;
        seen_revents |= (unsigned)p.revents;

        if (r < 0) { /* crossterm: Err(_) => break */
            say("[child] poll failed errno=%d(%s), thread exits\n", errno, strerror(errno));
            break;
        }
        if (mode == MODE_HUP_OK && (p.revents & (POLLHUP | POLLERR | POLLNVAL))) {
            say("[child] fix A: revents=%#x treated as EOF, exits after %.3fs\n", p.revents,
                mono() - t0);
            break;
        }
        if (mode == MODE_TCGETPGRP && tcgetpgrp(0) < 0 && errno == EIO) {
            say("[child] fix B: tcgetpgrp()==EIO, exits after %.3fs\n", mono() - t0);
            break;
        }

        if (r > 0 && (p.revents & POLLIN)) { /* crossterm: Ok(true) => read */
            char buf[64];
            errno = 0;
            ssize_t n = read(0, buf, sizeof buf); /* O_NONBLOCK, see main() */
            if (n == 0) {
                eof++;
            } else if (n < 0) {
                rderr++;
                if (mode == MODE_CROSSTERM) break;
            }
        } else if (r > 0) {
            hup_retry++; /* revents without POLLIN -> crossterm sees "no event" */
        }

        if (!hung && (p.revents & (POLLHUP | POLLERR | POLLNVAL))) {
            hung = 1;
            say("\n[child] hang-up detected at %.3fs, sampling from here on\n", mono() - t0);
            diagnose(0);
        }

        int sec = (int)(mono() - t0);
        if (sec != last_sec && last_sec >= 0) {
            double cpu = cpu_sec();
            say("[child] t=%ds  iters/s=%-9ld cpu=%.0f%%  revents=%#x\n", last_sec, iters_sec,
                (cpu - lastcpu) * 100.0, seen_revents);
            lastcpu = cpu;
            last_sec = sec;
            iters_sec = 0;
        } else if (last_sec < 0) {
            last_sec = sec;
        }
    }

    double cpu = cpu_sec();
    say("[child] done: iters=%ld cpu=%.3fs avg=%.0f%% (window %.1fs) "
        "hup_retry=%ld eof=%ld read_err=%ld revents=%#x\n",
        iters_total, cpu, cpu / (mono() - t0) * 100.0, mono() - t0, hup_retry, eof, rderr,
        seen_revents);
    _exit(0);
}

static void on_alarm(int s) {
    (void)s;
    const char m[] = "\n[parent] hard timeout (leftover children: pkill pty_hangup_spin)\n";
    ssize_t w = write(1, m, sizeof m - 1);
    (void)w;
    _exit(2);
}

int main(int argc, char **argv) {
    int mode = argc > 1 ? atoi(argv[1]) : MODE_CROSSTERM;
    int secs = argc > 2 ? atoi(argv[2]) : 6;
    double keep_dup = argc > 3 ? atof(argv[3]) : 0.0;

    const char *ptmx = getenv("PTMX");
    int master = ptmx ? open(ptmx, O_RDWR | O_NOCTTY) : posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) { perror("open ptmx"); return 1; }
    if (grantpt(master) || unlockpt(master)) { perror("grantpt/unlockpt"); return 1; }
    char *slave_name = ptsname(master);
    if (!slave_name) { perror("ptsname"); return 1; }

    int rp[2];
    if (pipe(rp)) { perror("pipe"); return 1; }

    signal(SIGALRM, on_alarm);
    alarm((unsigned)(secs + 4));
    printf("=== mode=%d total=%ds keep_dup=%.1fs slave=%s ===\n", mode, secs, keep_dup, slave_name);
    printf("phase 1: master alive, writing input\n");
    printf("phase 2: master closed = daemon/app gone\n\n");
    fflush(stdout);

    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 1; }
    if (pid == 0) {
        close(rp[0]);
        report_fd = rp[1];
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) { perror("open slave"); _exit(1); }
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        int fl = fcntl(0, F_GETFL);
        fcntl(0, F_SETFL, fl | O_NONBLOCK);
        child_loop(mode, secs);
    }

    close(rp[1]);
    for (int i = 0; i < 5; i++) { /* the app: steady input for 1 s */
        ssize_t w = write(master, "k", 1);
        (void)w;
        usleep(200000);
    }
    if (keep_dup > 0) {
        printf("[parent] keeping the master dup open for %.1fs (app still holds it)\n", keep_dup);
        fflush(stdout);
        usleep((useconds_t)(keep_dup * 1e6));
    }
    printf("[parent] closing the master (daemon/app gone)\n");
    fflush(stdout);
    if (getenv("UMOUNT")) {
        printf("[parent] lazy-unmounting %s with the master still open\n", getenv("UMOUNT"));
        fflush(stdout);
        pid_t u = fork();
        if (u == 0) {
            execl("/bin/umount", "umount", "-l", getenv("UMOUNT"), (char *)0);
            _exit(127);
        }
        int us;
        waitpid(u, &us, 0);
    } else {
        close(master);
    }

    char buf[512];
    ssize_t n;
    while ((n = read(rp[0], buf, sizeof buf - 1)) > 0) {
        buf[n] = 0;
        fputs(buf, stdout);
        fflush(stdout);
    }

    int status = 0;
    struct rusage ru;
    wait4(pid, &status, 0, &ru);
    double wall = (double)ru.ru_utime.tv_sec + ru.ru_utime.tv_usec / 1e6 +
                  (double)ru.ru_stime.tv_sec + ru.ru_stime.tv_usec / 1e6;
    printf("\n[parent] wait4: cpu=%.3fs (user=%.3fs sys=%.3fs) status=%d\n", wall,
           (double)ru.ru_utime.tv_sec + ru.ru_utime.tv_usec / 1e6,
           (double)ru.ru_stime.tv_sec + ru.ru_stime.tv_usec / 1e6,
           WIFEXITED(status) ? WEXITSTATUS(status) : -1);
    return 0;
}
