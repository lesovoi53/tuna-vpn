"""Post-R8 contract gate for reflection-based models (run against the shipped variant)."""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
mapping = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'app/build/outputs/mapping/release/mapping.txt'
rules = (root / 'app/proguard-rules.pro').read_text(encoding='utf-8')
models = set(re.findall(r'^-keep class ([\w.$]+) \{ \*; \}', rules, re.M))
# These contracts must remain checked even if someone accidentally removes a keep rule.
models.update({'com.wireturn.app.domain.SplitDnsSettings',
               'com.wireturn.app.domain.DnsRecoverySettings',
               'com.wireturn.app.domain.SubscriptionPolicyParser$Resolver',
               'com.wireturn.app.domain.MasterDnsResolverResult',
               'com.wireturn.app.domain.MasterDnsSavedScan',
               'com.wireturn.app.domain.MasterDnsPoolMeasurement'})
seen = set()
errors = []
active = None
fields = 0
for line in mapping.read_text(encoding='utf-8').splitlines():
    if line and not line[0].isspace() and not line.startswith('#'):
        match = re.fullmatch(r'(.+) -> (.+):', line)
        active = match.group(1) if match and match.group(1) in models else None
        if active:
            seen.add(active)
            if match.group(2) != active:
                errors.append(active + ': class renamed')
    elif active and ' -> ' in line and '(' not in line and not line.lstrip().startswith('#'):
        match = re.fullmatch(r'\s+\S+ (\S+) -> (\S+)', line)
        if match:
            fields += 1
            if match.group(1) != match.group(2):
                errors.append(active + '.' + match.group(1) + ': field renamed')
errors.extend(name + ': missing' for name in sorted(models - seen))
if errors:
    print('\n'.join(errors))
    raise SystemExit(1)
print(f'R8 reflection contracts preserved: {len(seen)} models, {fields} fields.')
