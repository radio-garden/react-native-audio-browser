/**
 * Strips the `friend bool operator==(...) = default;` (with its `public:`
 * access specifier) that nitrogen (>= 0.32) emits on generated C++ structs —
 * but only from structs containing a `std::vector<double>` field.
 *
 * Under Swift 6.2 / Xcode 26.x, the defaulted operator's instantiation of
 * `std::vector<double>::operator==` makes the compiler drop the automatic
 * CxxRandomAccessCollection conformance for that specialization module-wide,
 * so every generated Swift getter touching a number[] fails with "value of
 * type 'std.__1.vector<CDouble, ...>' has no member 'map'". Other vector
 * specializations (e.g. std::string) are unaffected, so their structs keep
 * the operator. It is a DX convenience, not a runtime requirement, and
 * nothing in this library compares generated structs.
 *
 * See https://github.com/radio-garden/react-native-audio-browser/issues/88
 * (upstream: mrousavy/nitro#1186, mrousavy/nitro#1376) — including the
 * criteria for removing this script and its `codegen` hook in package.json.
 */
const path = require('node:path')
const { readdir, readFile, writeFile } = require('node:fs/promises')

const structsDir = path.join(process.cwd(), 'nitrogen/generated/shared/c++')
const operatorBlock =
  /\n[ \t]*public:\n[ \t]*friend bool operator==\(.*\) = default;\n/g

const strip = async () => {
  const files = await readdir(structsDir)
  const stripped = []
  for (const file of files) {
    if (!file.endsWith('.hpp')) continue
    const filePath = path.join(structsDir, file)
    const content = await readFile(filePath, { encoding: 'utf8' })
    if (!content.includes('std::vector<double>')) continue
    const patched = content.replace(operatorBlock, '\n')
    if (patched !== content) {
      await writeFile(filePath, patched)
      stripped.push(file)
    }
  }
  console.log(
    `strip-struct-equality: stripped operator== from ${stripped.length} struct(s): ${stripped.join(', ')}`
  )
}

strip()
