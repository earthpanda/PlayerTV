#!/usr/bin/env python3
"""Read-only architecture checks for the literal four-module Kotlin DSL skeleton.

Not a Kotlin/Gradle parser. Exit 0: supported checks pass; 1: violation;
2: unsupported/incomplete configuration requiring review. No Android build is run.
"""
import argparse
import re
from pathlib import Path


ALLOWED = {"app": {"base", "model", "support"}, "model": {"base", "support"},
           "support": {"base"}, "base": set()}


def without_comments(source: str, mask_strings: bool = False) -> str:
    """Preserve line numbers and quoted literals; discard line/nested block comments."""
    result = []
    i = 0
    while i < len(source):
        if source.startswith('//', i):
            end = source.find('\n', i)
            end = len(source) if end < 0 else end
        elif source.startswith('/*', i):
            end, depth = i + 2, 1
            while end < len(source) and depth:
                if source.startswith('/*', end):
                    depth += 1
                    end += 2
                elif source.startswith('*/', end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
        else:
            delimiter = next((q for q in ('"""', '"', "'") if source.startswith(q, i)), None)
            if delimiter:
                end = i + len(delimiter)
                while end < len(source):
                    if len(delimiter) == 1 and source[end] == '\\':
                        end += 2
                    elif source.startswith(delimiter, end):
                        end += len(delimiter)
                        break
                    else:
                        end += 1
                literal = source[i:end]
                result.append(''.join('\n' if c == '\n' else ' ' for c in literal) if mask_strings else literal)
                i = end
                continue
            result.append(source[i])
            i += 1
            continue
        result.append(''.join('\n' if c == '\n' else ' ' for c in source[i:end]))
        i = end
    return ''.join(result)


def check(root: Path) -> tuple[list[str], list[str], int]:
    errors, reviews, namespaces = [], [], {}

    def report(bucket: list[str], rule: str, path: Path, source: str, offset: int, message: str):
        line = source.count('\n', 0, offset) + 1
        bucket.append(f'{rule} {path.relative_to(root).as_posix()}:{line} {message}')

    for module, allowed in ALLOWED.items():
        path = root / module / 'build.gradle.kts'
        if not path.is_file():
            reviews.append(f'CONFIG {module}/build.gradle.kts:1 missing Kotlin DSL module')
            continue
        source = without_comments(path.read_text(encoding='utf-8-sig'))
        namespace = re.search(r'\bnamespace\s*=\s*"([\w.]+)"', source)
        if namespace:
            namespaces[module] = namespace[1]
        else:
            reviews.append(f'CONFIG {module}/build.gradle.kts:1 literal namespace required')
        literal = re.compile(r'\bproject\(\s*(?:path\s*=\s*)?"(:[\w:-]+)"\s*\)')
        matches = list(literal.finditer(source))
        for match in matches:
            target = match[1][1:]
            if target not in allowed:
                report(errors, 'MODULE_DIRECTION', path, source, match.start(), f'{module} -> {target}')
        covered = literal.sub('', source)
        if re.search(r'\bproject\s*\(|\bprojects\.', covered):
            reviews.append(f'CONFIG {module}/build.gradle.kts:1 nonliteral project reference; review manually')

    if len(set(namespaces.values())) != len(namespaces):
        reviews.append('CONFIG namespace values must be distinct')
    ordered = sorted(namespaces.items(), key=lambda item: len(item[1]), reverse=True)
    count = 0
    for module, allowed in ALLOWED.items():
        for path in sorted((root / module / 'src').rglob('*.kt')):
            if path.is_symlink():
                reviews.append(f'CONFIG {path.relative_to(root).as_posix()}:1 symlink requires review')
                continue
            count += 1
            source = without_comments(path.read_text(encoding='utf-8-sig'), mask_strings=True)
            package = re.search(r'^[ \t]*package\s+([\w.]+)', source, re.M)
            model_prefix = namespaces.get('model', '')
            pure_business = module == 'model' and package and model_prefix and any(
                package[1].startswith(model_prefix + '.' + part)
                for part in ('bean.domain.', 'bean.ui.', 'contract.', 'state.', 'usecase.'))
            for match in re.finditer(r'^[ \t]*import\s+([\w.*]+)', source, re.M):
                name = match[1]
                owner = next((m for m, ns in ordered if name == ns or name.startswith(ns + '.')), None)
                if owner and owner != module and owner not in allowed:
                    report(errors, 'IMPORT_DIRECTION', path, source, match.start(), f'{module} imports {name}')
                if pure_business and re.match(
                    r'android\.(?:app\.|view\.|widget\.|content\.(?:Context|Intent)(?:$|\.)|graphics\.drawable\.)'
                    r'|androidx\.(?:activity\.|fragment\.|appcompat\.app\.)', name):
                    report(errors, 'BUSINESS_UI_IMPORT', path, source, match.start(), name)
    return errors, reviews, count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().root.resolve()
    try:
        errors, reviews, count = check(root)
    except (OSError, UnicodeError) as error:
        print(f'CONFIG {error}')
        return 2
    for finding in errors + reviews:
        print(finding)
    print(f'Architecture scan: {count} Kotlin files, {len(errors)} violations, {len(reviews)} manual reviews.')
    return 1 if errors else 2 if reviews else 0


if __name__ == '__main__':
    raise SystemExit(main())
