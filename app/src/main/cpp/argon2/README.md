# Argon2 reference implementation

These files are the reference C implementation of Argon2, the same library that KeePassXC links.

- Source: https://github.com/P-H-C/phc-winner-argon2, release `20190702`
- Archive: https://github.com/P-H-C/phc-winner-argon2/archive/refs/tags/20190702.tar.gz
- Archive SHA-256: `daf972a89577f8772602bf2eb38b6a3dd3d922bf5724d45e7f9589b5e830442c`
- License: CC0 1.0 or Apache 2.0, at your option. See `LICENSE`.

Only the files the library needs are copied, in their upstream layout: `include/argon2.h`, and
from `src/`, `argon2.c`, `core.c`, `encoding.c`, `ref.c`, `thread.c`, `blake2/blake2b.c` and
their headers. `opt.c`, which uses x86 SIMD, is left out, so every ABI builds the portable `ref.c`.

## Local changes

Every change is marked with a `KeeBerry` comment, so `grep -rn KeeBerry .` lists them all.

- `argon2.h` adds an optional `abort_cbk` and `abort_data` to `argon2_context`, and the
  `ARGON2_ABORTED` error code. `argon2.c` and `encoding.c` set the new fields to `NULL` wherever
  upstream fills in a context, and `argon2_error_message` describes the new code.
- `core.c` calls `abort_cbk` on the calling thread after each slice, which is a quarter of a pass,
  once every lane thread has been joined. A nonzero return stops the run with `ARGON2_ABORTED`.
  The app uses this to cancel an unlock partway through and to show how far key derivation got.
- `argon2_ctx` in `argon2.c` wipes and frees the Argon2 memory when filling it fails. Upstream
  returns without freeing it on that path, which aborting now takes.

When updating, copy the same files from the new release and apply these changes again.
