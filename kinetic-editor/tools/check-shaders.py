#!/usr/bin/env python3
"""Compiles the app's GLSL and checks it against the Kotlin that drives it.

Two things go wrong with a shader that no Kotlin compiler and no unit test can
catch, and both fail only on a device:

  1. The GLSL does not compile. Every frame of video is then black or the
     player dies, on every device at once.
  2. A uniform the Kotlin sets is not in the linked program. Drivers strip
     uniforms nothing reads, and GlProgram throws when asked to set one that
     is missing, so this is a crash on the first frame rather than a no-op.

Both are checked here with the Khronos reference compiler:

    sudo apt-get install glslang-tools     # provides glslangValidator
    python3 tools/check-shaders.py

The shaders declare no #version, so a GLES driver reads them as ESSL 1.00;
that directive is prepended here so glslang applies the same rules. Both sides
of the precision guard are compiled, because which one a device takes depends
on the device.

There are several programs. Each Kotlin class that builds one names its
fragment shader by its EditorShaders constant, and every uniform that class
sets is checked against that shader's linked program.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EFFECTS = os.path.join(ROOT, "app/src/main/java/com/kinetic/editor/effects")
SHADERS_KT = os.path.join(EFFECTS, "Shaders.kt")

# GL type enums as glslang reports them.
GL_FLOAT = "1406"
GL_FLOAT_VEC2 = "8b50"
GL_FLOAT_VEC3 = "8b51"
GL_FLOAT_VEC4 = "8b52"
GL_SAMPLER_2D = "8b5e"
GL_SAMPLER_EXTERNAL_OES = "8d66"

# What each GlProgram setter promises the uniform's type is.
SETTERS = {
    "setFloatUniform": {GL_FLOAT},
    "setFloatsUniform": {GL_FLOAT, GL_FLOAT_VEC2, GL_FLOAT_VEC3, GL_FLOAT_VEC4},
    "setSamplerTexIdUniform": {GL_SAMPLER_2D, GL_SAMPLER_EXTERNAL_OES},
}


def shader_sources():
    """Name -> GLSL, for every constant in Shaders.kt."""
    src = open(SHADERS_KT).read()
    found = dict(re.findall(r'const val (\w+) = """(.*?)"""', src, re.S))
    if "VERTEX" not in found or "FRAGMENT" not in found:
        sys.exit(f"could not find the shaders in {SHADERS_KT}")
    return found


def run_glslang(body, suffix, extra):
    with tempfile.NamedTemporaryFile("w", suffix=suffix, delete=False) as f:
        f.write("#version 100\n" + body)
        path = f.name
    result = subprocess.run(
        ["glslangValidator"] + extra + [path], capture_output=True, text=True,
    )
    os.unlink(path)
    return result, path


def compiles(name, body, suffix):
    """Compiles both precision paths; returns True when both succeed."""
    ok = True
    for highp in (True, False):
        # -D needs -l (link), which is fine: linking is what a driver does too.
        extra = ["-l", "-DGL_FRAGMENT_PRECISION_HIGH=1"] if highp else []
        result, path = run_glslang(body, suffix, extra)
        label = f"{name} ({'highp' if highp else 'mediump'})"
        if result.returncode != 0:
            ok = False
            print(f"FAIL  {label} does not compile")
            print((result.stdout + result.stderr).replace(path, name))
        else:
            print(f"ok    {label} compiles as ESSL 1.00")
    return ok


def linked_uniforms(name, body):
    """Name -> GL type, for the uniforms that survive linking."""
    result, path = run_glslang(body, ".frag", ["-l", "-q"])
    if result.returncode != 0:
        sys.exit(f"could not reflect {name}:\n" + result.stdout + result.stderr)
    found = {}
    # Only the uniform section: the report goes on to list pipeline inputs and
    # outputs in the same shape, and gl_FragColor is not a uniform.
    in_uniforms = False
    for line in result.stdout.splitlines():
        if line.startswith("Uniform reflection:"):
            in_uniforms = True
            continue
        if line.rstrip().endswith("reflection:"):
            in_uniforms = False
            continue
        m = re.match(r"(\w+): offset .*?, type ([0-9a-f]+),", line)
        if in_uniforms and m:
            found[m.group(1)] = m.group(2)
    return found


def programs():
    """(class, fragment constant, {uniform: setter}) for every class that builds a program."""
    out = []
    for path in sorted(glob.glob(os.path.join(EFFECTS, "*.kt"))):
        src = open(path).read()
        # Split at top-level class declarations; each chunk is one class body.
        chunks = re.split(r"^(?=(?:private |internal |abstract |open )*class )", src, flags=re.M)
        for chunk in chunks:
            m = re.match(r"(?:private |internal |abstract |open )*class (\w+)", chunk)
            if not m:
                continue
            frags = {n for n in re.findall(r"EditorShaders\.([A-Z_]+)", chunk) if n != "VERTEX"}
            wanted = {}
            for setter in SETTERS:
                for name in re.findall(setter + r'\(\s*"(\w+)"', chunk):
                    wanted[name] = setter
            if not frags and not wanted:
                continue
            if len(frags) != 1:
                sys.exit(f"{m.group(1)} in {os.path.basename(path)} names {len(frags)} fragment shaders; expected 1")
            out.append((m.group(1), frags.pop(), wanted))
    return out


def main():
    if subprocess.run(["which", "glslangValidator"], capture_output=True).returncode != 0:
        sys.exit("glslangValidator not found: apt-get install glslang-tools")

    sources = shader_sources()
    ok = True
    for name, body in sources.items():
        ok &= compiles(name, body, ".vert" if name == "VERTEX" else ".frag")

    checked = set()
    for cls, frag, wanted in programs():
        if frag not in sources:
            sys.exit(f"{cls} names EditorShaders.{frag}, which does not exist")
        if not wanted:
            sys.exit(f"{cls} builds {frag} but sets no uniforms — has the API changed?")
        checked.add(frag)
        linked = linked_uniforms(frag, sources[frag])
        print(f"\n{cls} -> EditorShaders.{frag}")
        for name, setter in sorted(wanted.items()):
            if name not in linked:
                ok = False
                print(f"FAIL  {name} is set by {setter} but is not in the linked program")
                print("      (declared but never read? the driver strips those, and setting one throws)")
            elif linked[name] not in SETTERS[setter]:
                ok = False
                print(f"FAIL  {name} is type {linked[name]} but {setter} writes {SETTERS[setter]}")
            else:
                print(f"ok    {name} is linked and matches {setter}")
        for name in sorted(set(linked) - set(wanted)):
            print(f"warn  {name} is in the shader but nothing sets it; it keeps its default")

    for name in sources:
        if name != "VERTEX" and name not in checked:
            ok = False
            print(f"FAIL  EditorShaders.{name} is not built by any class")

    print("\nSHADERS OK" if ok else "\nSHADER CHECK FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
