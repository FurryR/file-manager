#!/usr/bin/env python3
"""Prepare PulseAudio 17.0 for a static, configuration-free Android host."""
from pathlib import Path
import sys

source = Path(sys.argv[1])
for path in (source / "src").rglob("meson.build"):
    text = path.read_text()
    path.write_text(text.replace("shared_library(", "library("))

path = source / "meson.build"
text = path.read_text()
start = text.find("if cc.has_function('dgettext')")
if start >= 0:
    end = text.index("# Symbols", start)
    text = text[:start] + "libintl_dep = dependency('', required: false)\n\n" + text[end:]
text = text.replace("cdata.set('ENABLE_NLS', 1)", "# Translations belong to Android resources, not the embedded engine.")
text = text.replace("  'execinfo.h',", "  # Bionic backtrace APIs are unavailable at API 26.")
text = text.replace("if cc.has_header_symbol('pthread.h', 'PTHREAD_PRIO_INHERIT')",
                    "if host_machine.system() != 'android' and cc.has_header_symbol('pthread.h', 'PTHREAD_PRIO_INHERIT')")
path.write_text(text)

# The JVM process has no C main(). Keep upstream's /proc/self/exe and prctl
# fallbacks instead of importing a non-system weak symbol into the host .so.
path = source / "src/pulse/util.c"
text = path.read_text().replace(
    "#if defined(HAVE_DLADDR) && defined(PA_GCC_WEAKREF)",
    "#if defined(HAVE_DLADDR) && defined(PA_GCC_WEAKREF) && !defined(__ANDROID__)")
path.write_text(text)

# Modules and command-line tools are not shipped. Build only the core/protocol
# archives; keep real libltdl linked statically for the core's module API.
path = source / "src/meson.build"
text = path.read_text().replace("  subdir('daemon')", "  # No standalone daemon executable.")
text = text.replace("  subdir('modules')", "  # Devices are implemented directly by pulse_host.c.")
text = text.replace("subdir('utils')", "# No command-line clients.")
path.write_text(text)
