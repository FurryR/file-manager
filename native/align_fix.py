#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# https://github.com/Lzhiyong/termux-ndk/blob/master/patches/align_fix.py
#
# Raise PT_TLS segment alignment to the minimum required by the platform:
#   32-bit → 32 bytes
#   64-bit → 64 bytes

import struct
import sys
import os

if len(sys.argv) < 2:
    print(f'Usage: {os.path.basename(sys.argv[0])} <elf_file>')
    sys.exit(1)

path = sys.argv[1]
with open(path, 'r+b') as f:
    ident = f.read(16)
    if ident[:4] != b'\x7fELF':
        print(f'{path}: not an ELF file', file=sys.stderr)
        sys.exit(1)

    is_64bit = ident[4] == 2
    min_align = 64 if is_64bit else 32

    if is_64bit:
        f.seek(32)
        offset = struct.unpack('<Q', f.read(8))[0]
        f.seek(54)
        phsize = struct.unpack('<H', f.read(2))[0]
        phnum = struct.unpack('<H', f.read(2))[0]
    else:
        f.seek(28)
        offset = struct.unpack('<I', f.read(4))[0]
        f.seek(42)
        phsize = struct.unpack('<H', f.read(2))[0]
        phnum = struct.unpack('<H', f.read(2))[0]

    patched = False
    for i in range(phnum):
        f.seek(offset + i * phsize)
        p_type = struct.unpack('<I', f.read(4))[0]
        if p_type != 7:
            continue

        if is_64bit:
            f.seek(48 - 4, 1)
            align = struct.unpack('<Q', f.read(8))[0]
        else:
            f.seek(28 - 4, 1)
            align = struct.unpack('<I', f.read(4))[0]

        if align < min_align:
            if is_64bit:
                f.seek(-8, 1)
                f.write(struct.pack('<Q', min_align))
            else:
                f.seek(-4, 1)
                f.write(struct.pack('<I', min_align))
            print(f'{path}: PT_TLS alignment {align} -> {min_align}')
            patched = True
        else:
            print(f'{path}: PT_TLS alignment {align} (ok)')
        break

    if not patched:
        print(f'{path}: no PT_TLS segment found', file=sys.stderr)
