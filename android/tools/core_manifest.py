"""Record the exact packaged cores and adapter contract, without reading user profiles."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/native'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def generate():
    adapter_version = re.search(r'^project\.versionName=(.+)$',
        (ROOT / 'gradle.properties').read_text(encoding='utf-8'), re.M).group(1).strip()
    pinned = json.loads((ROOT / 'native-lock.json').read_text(encoding='utf-8'))['SingBoxExtended']
    sb = json.loads((ASSETS / 'singbox.json').read_text(encoding='utf-8-sig'))
    assert sb['commit'] == pinned['commit'] and sb['buildTags'] == pinned['buildTags']
    assert sb['utls'] == pinned['utls']
    assert sb.get('cronet') == pinned.get('cronet')
    assert sb.get('sourceOverlay') == pinned.get('sourceOverlay')
    assert sb.get('dependencyOverlay') == pinned.get('dependencyOverlay')
    for path, expected in sb['dependencyFiles'].items():
        assert digest(ROOT / path).lower() == expected.lower(), 'Native dependency input changed: ' + path
    binary_dir = ROOT / 'app/src/main/jniLibs/arm64-v8a'
    assert digest(binary_dir / 'libsingbox.so').lower() == sb['sha256'].lower()
    xray_provenance = json.loads((ROOT / 'native/routing/provenance-current.json').read_text(encoding='utf-8-sig'))
    for path, expected in xray_provenance['sources'].items():
        assert digest(ROOT / path) == expected, 'Xray native source changed: rebuild before manifest generation: ' + path
    assert digest(binary_dir / 'libxray.so') == xray_provenance['binaries']['libxray-network.so'], 'Packaged Xray differs from its build provenance'
    mieru = json.loads((ROOT / 'native/mieru/provenance.json').read_text(encoding='utf-8-sig'))
    assert digest(binary_dir / 'libmieru.so') == mieru['binarySha256']
    for path, expected in mieru['sources'].items():
        assert digest(ROOT / path) == expected, 'Mieru source changed: ' + path
    qwdtt = json.loads((ROOT / 'native/qwdtt/provenance.json').read_text(encoding='utf-8-sig'))
    assert qwdtt['commit'] == 'a296c57eaba69bb9479a24f9157856490890e47d'
    assert digest(binary_dir / 'libqwdtt.so') == qwdtt['binarySha256']
    runtime = json.loads((ROOT / 'native/runtime-launcher/provenance.json').read_text(encoding='utf-8-sig'))
    assert runtime['protocol'] == 'TUNAMEM1' and runtime['api'] == 26 and runtime['abi'] == 'arm64-v8a'
    assert digest(binary_dir / 'libtuna-runtime.so') == runtime['binarySha256']
    assert digest(binary_dir / 'libtuna-memory.so') == runtime['jniBinarySha256']
    for component, provenance in (('qWDTT', qwdtt), ('Memory runtime', runtime)):
        for path, expected in provenance['sources'].items():
            assert digest(ROOT / path) == expected, component + ' source changed: ' + path
    prebuilt = json.loads((ROOT / 'native/prebuilt-provenance.json').read_text(encoding='utf-8'))
    assert prebuilt['source'] == json.loads((ROOT / 'native-lock.json').read_text())['WireTurnApk']
    for name, expected in prebuilt['binaries'].items():
        assert digest(binary_dir / name) == expected, 'Prebuilt component changed: ' + name
    master = json.loads((ROOT / 'native/masterdns/provenance.json').read_text(encoding='utf-8-sig'))
    assert digest(binary_dir / 'libmasterdns.so') == master['binarySha256'].lower()
    assert digest(ROOT / 'native/masterdns/main.go') == master['adapterSha256'].lower()
    assert digest(ROOT / 'native/masterdns/prepare.py') == master['overlayScriptSha256'].lower()
    assert digest(ROOT / 'native/masterdns/scan.go.inc') == master['scanOverlaySha256'].lower()
    assert digest(ROOT / 'native/masterdns/scan_test.go') == master['scanTestsSha256'].lower()
    assert digest(ROOT / 'native/masterdns/domain_resolvers.go') == master['domainResolversSha256'].lower()
    assert digest(ROOT / 'native/masterdns/domain_resolvers_test.go') == master['domainResolverTestsSha256'].lower()
    assert digest(ROOT / 'native/masterdns/domain_pairs_test.go') == master['domainPairTestsSha256'].lower()
    assert digest(ROOT / 'native/masterdns/balancer_test.go') == master['balancerTestsSha256'].lower()
    assert digest(ROOT / 'native/masterdns/diagnostic.go') == master['diagnosticOverlaySha256'].lower()
    assert digest(ROOT / 'native/masterdns/adapter_test.go') == master['adapterTestsSha256'].lower()
    pool_sources = {path.name: digest(path) for path in (ROOT / 'native/masterdns').glob('pool*') if path.is_file()}
    assert pool_sources == {name: value.lower() for name, value in master['poolSourcesSha256'].items()}, \
        'MasterDNS pool source changed: rebuild before manifest generation'
    dep = re.search(r'github.com/xtls/xray-core\s+(\S+)', (ROOT / 'external/vless-client/go.mod').read_text()).group(1)
    paths = ['external/vless-client/main.go', 'external/vless-client/outbound_patch.go',
             'app/src/main/java/com/wireturn/app/kernel/SystemDnsBridge.kt',
             'app/src/main/java/com/wireturn/app/kernel/SystemDnsWire.kt',
             'app/src/main/java/com/wireturn/app/kernel/PhysicalNetwork.kt',
             'app/src/main/java/com/wireturn/app/kernel/EffectiveDnsBootstrap.kt',
             'app/src/main/java/com/wireturn/app/kernel/PrivateRuntimeDirectory.kt',
             'app/src/main/java/com/wireturn/app/kernel/MemoryConfigs.kt',
             'app/src/main/java/com/wireturn/app/kernel/MemoryFileDescriptor.kt',
             'app/src/main/java/com/wireturn/app/kernel/PrivateRuntimeConfigFile.kt',
             'app/src/main/java/com/wireturn/app/kernel/TunAddressPolicy.kt',
             'app/src/main/java/com/wireturn/app/data/security/EncryptedPreferences.kt',
             'app/src/main/java/com/wireturn/app/data/security/AndroidPreferenceCipher.kt',
             'native/routing/policy.go', 'native/routing/prepare.py', 'native/routing/bootstrap.go',
             'native/routing/xhttp_alpn_test.go',
             'app/src/main/java/com/wireturn/app/domain/IsolatedProfileTest.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsCatalog.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsResolverCheck.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsScanWire.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsScanRelay.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsGoodput.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsResolverRecommendation.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/MasterDnsPerformance.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/MasterDnsPoolSelection.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsPoolPlan.kt',
             'app/src/main/java/com/wireturn/app/domain/MasterDnsScanCache.kt',
             'app/src/main/java/com/wireturn/app/data/MasterDnsResolverSelection.kt',
             'app/src/main/java/com/wireturn/app/viewmodel/MasterDnsResolverViewModel.kt',
             'app/src/main/java/com/wireturn/app/ui/activities/MasterDnsResolversActivity.kt',
             'app/src/main/java/com/wireturn/app/domain/IsolatedSnellResolver.kt',
             'app/src/main/java/com/wireturn/app/kernel/SnellDestinationResolver.kt',
             'app/src/main/java/com/wireturn/app/domain/CloudflareProfile.kt',
             'app/src/main/java/com/wireturn/app/domain/CloudflareScanner.kt',
             'app/src/main/java/com/wireturn/app/domain/CfScanPipeline.kt',
             'app/src/main/java/com/wireturn/app/domain/CfProfileUpdate.kt',
             'app/src/main/java/com/wireturn/app/domain/TrafficSampler.kt',
             'app/src/main/java/com/wireturn/app/domain/HomeDiagnosticFlow.kt',
             'app/src/main/java/com/wireturn/app/domain/ProxyHttpText.kt',
             'app/src/main/java/com/wireturn/app/domain/ServerAccessProbe.kt',
             'app/src/main/java/com/wireturn/app/domain/ExitObservation.kt',
             'app/src/main/java/com/wireturn/app/viewmodel/MainViewModel.kt',
             'app/src/main/java/com/wireturn/app/ui/screens/HomeScreen.kt',
             'app/src/main/java/com/wireturn/app/domain/ProfileListOrder.kt',
             'app/src/main/java/com/wireturn/app/domain/GroupProbe.kt',
             'app/src/main/java/com/wireturn/app/domain/CfTcpRelay.kt',
             'app/src/main/java/com/wireturn/app/domain/IsolatedProfileReadiness.kt',
             'app/src/main/java/com/wireturn/app/domain/ManualGroupPolicy.kt',
             'app/src/main/java/com/wireturn/app/domain/ProfileUrlTestBatch.kt',
             'app/src/main/java/com/wireturn/app/domain/WgProbeGuard.kt',
             'app/src/main/java/com/wireturn/app/domain/ProfileEncoder.kt',
             'app/src/main/java/com/wireturn/app/domain/ProfileArchive.kt',
             'app/src/main/java/com/wireturn/app/ui/activities/AddProfileActivity.kt',
             'app/src/main/java/com/wireturn/app/ui/screens/ProfilesUI.kt',
             'app/src/main/java/com/wireturn/app/domain/LegacyProfileImport.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionProfileRefresh.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionHttpTransport.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionDownload.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionAddressPolicy.kt',
    'app/src/main/java/com/wireturn/app/domain/ConnectionGroupRunner.kt',
    'app/src/main/java/com/wireturn/app/domain/GroupRuntime.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/CoreCapabilities.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/VpnEnginePolicy.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionIdentity.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionGroupMerge.kt',
             'app/src/main/java/com/wireturn/app/kernel/ProcessTermination.kt',
             'app/src/main/java/com/wireturn/app/kernel/NativeTunnelRuntime.kt',
             'app/src/main/java/com/wireturn/app/kernel/AndroidProcessSignal.kt',
             'app/src/main/java/com/wireturn/app/kernel/RestartingProcessGroup.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/VpnOptions.kt',
             'app/src/main/java/com/wireturn/app/data/StoredProfiles.kt',
              'app/src/main/java/com/wireturn/app/data/ImportedProfileActivation.kt',
              'app/src/main/java/com/wireturn/app/data/SelectedProfileActivation.kt',
             'app/src/main/java/com/wireturn/app/data/ClientConfig.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/VpnConfig.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/VpnImportText.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/VlessId.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/AdditionalVpnProtocols.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/NaiveUri.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/AwgUri.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/TrustTunnelUri.kt',
             'app/src/main/java/com/wireturn/app/domain/AwgProfileDedup.kt',
             'app/src/main/java/com/wireturn/app/domain/ProfileReveal.kt',
             'app/src/main/java/com/wireturn/app/kernel/SingBoxConfig.kt',
             'app/src/main/java/com/wireturn/app/kernel/VpnCoreLog.kt',
             'app/src/main/java/com/wireturn/app/kernel/VpnNativeValidation.kt',
             'app/src/main/java/com/wireturn/app/kernel/ExtendedVless.kt',
             'app/src/main/java/com/wireturn/app/kernel/XrayVpnConfig.kt',
             'app/src/main/java/com/wireturn/app/kernel/RawXrayConfig.kt',
             'app/src/main/java/com/wireturn/app/data/BootstrapDns.kt',
             'app/src/main/java/com/wireturn/app/data/RoutingPolicy.kt',
             'app/src/main/java/com/wireturn/app/domain/SplitDnsSettings.kt',
             'app/src/main/java/com/wireturn/app/domain/TcpOnlyDns.kt',
             'app/src/main/java/com/wireturn/app/kernel/SingBoxNetworkCompiler.kt',
             'app/src/main/java/com/wireturn/app/kernel/XrayNetworkCompiler.kt',
             'app/src/main/java/com/wireturn/app/kernel/RoutingConfigCheck.kt',
             'app/src/main/java/com/wireturn/app/domain/RoutingRegex.kt',
             'app/src/main/java/com/wireturn/app/domain/VlessFingerprintImport.kt',
             'app/src/main/java/com/wireturn/app/domain/VpnFailurePolicy.kt',
             'app/src/main/java/com/wireturn/app/CoreService.kt',
             'app/src/main/java/com/wireturn/app/HevVpnService.kt',
             'app/src/main/java/com/wireturn/app/domain/GeoRuleSets.kt',
             'app/src/main/java/com/wireturn/app/domain/ProfileManager.kt',
             'app/src/main/java/com/wireturn/app/domain/CoreManager.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionTextFormat.kt',
             'app/src/main/java/com/wireturn/app/domain/ProfileRouting.kt',
             'app/src/main/java/com/wireturn/app/domain/RoutingAssetStore.kt',
             'app/src/main/java/com/wireturn/app/domain/RoutingUpdates.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionRoutingAssets.kt',
             'app/src/main/java/com/wireturn/app/data/NetworkPreferences.kt',
             'app/src/main/java/com/wireturn/app/data/AppPreferences.kt',
             'app/src/main/java/com/wireturn/app/data/AutoLaunchPreferences.kt',
             'app/src/main/java/com/wireturn/app/data/ProfileListEdit.kt',
             'app/src/main/java/com/wireturn/app/data/SubscriptionEdit.kt',
             'app/src/main/java/com/wireturn/app/data/SubscriptionImportCommit.kt',
             'app/src/main/java/com/wireturn/app/data/SubscriptionDeletion.kt',
             'app/src/main/java/com/wireturn/app/data/kernel/WebdavConfig.kt',
             'app/src/main/java/com/wireturn/app/kernel/WebdavKernel.kt',
             'app/src/main/java/com/wireturn/app/kernel/WebdavRuntimeConfig.kt',
             'app/src/main/java/com/wireturn/app/kernel/ChainCompatibility.kt',
             'app/src/main/java/com/wireturn/app/kernel/PoolPortReservation.kt',
             'app/src/main/java/com/wireturn/app/domain/SubscriptionProfileMerge.kt',
             'app/src/main/java/com/wireturn/app/data/ProfileActivation.kt']
    webdav = json.loads((ROOT / 'native/webdav/provenance.json').read_text(encoding='utf-8-sig'))
    webdav_lock = json.loads((ROOT / 'native-lock.json').read_text(encoding='utf-8'))['WebDAV']
    assert webdav['commit'] == webdav_lock['commit']
    assert digest(binary_dir / 'libwebdav.so') == webdav['binaries']['libwebdav.so']
    for path, expected in webdav['sources'].items():
        assert digest(ROOT / path) == expected, 'WebDAV source changed: ' + path
    manifest = {
        'schemaVersion': 1, 'adapterVersion': adapter_version, 'abi': 'arm64-v8a',
        'defaultEnginePolicy': {'vless': 'xray', 'hysteria2': 'xray', 'trojan': 'xray',
                                'shadowsocks': 'sing-box', 'tuic': 'sing-box', 'anytls': 'sing-box',
                                'snell': 'sing-box', 'naive': 'sing-box', 'mieru': 'mieru',
                                'awg': 'sing-box', 'wireguard': 'sing-box', 'trusttunnel': 'sing-box',
                                'exceptions': 'preserve native options; static capability validation; no network probing'},
        'webDAV': {'commit': webdav_lock['commit'], 'sha256': digest(binary_dir / 'libwebdav.so'), 'socksAuth': True, 'udp': 'DNS port 53 only; relayed over TCP'},
        'singBox': sb, 'xray': {'version': '26.9.9', 'dependency': dep, 'sourceOverlay': 'xhttp-explicit-http1-alpn-v1', 'sha256': digest(binary_dir / 'libxray.so')},
        'adapterSources': {path: digest(ROOT / path) for path in paths},
        'standaloneEditors': {'vless': ['sing-box', 'xray'], 'hysteria2': ['sing-box', 'xray'], 'trojan': ['sing-box', 'xray'], 'shadowsocks': ['sing-box'], 'tuic': ['sing-box'], 'anytls': ['sing-box'], 'snell-v4': ['sing-box'], 'snell-v6': ['sing-box'], 'awg': ['sing-box'], 'wireguard': ['sing-box'], 'trusttunnel': ['sing-box'], 'naive': ['sing-box'], 'mieru': ['mieru-3.38.1']},
        'routingOwner': 'xray', 'tunOwner': 'hev',
        'optionalRoutingOwner': 'sing-box-extended (experimental, authenticated SOCKS profiles without chains)',
        'configurationValidation': 'packaged-native-check',
        'memoryRuntime': {'protocol': runtime['protocol'], 'sha256': runtime['binarySha256'], 'jniSha256': runtime['jniBinarySha256']},
        'newFeaturesDeviceValidation': 'pending-user-installation',
    }
    previous_path = ASSETS / 'capabilities.json'
    previous = json.loads(previous_path.read_text(encoding='utf-8')) if previous_path.exists() else {}
    diff = {key: {'before': previous.get(key), 'after': value} for key, value in manifest.items() if previous.get(key) != value}
    (ROOT / 'build').mkdir(exist_ok=True)
    (ROOT / 'build/core-capabilities-diff.json').write_text(json.dumps(diff, indent=2), encoding='utf-8')
    previous_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    # Actual shipped components only. Fork flags refer to native overlays, not UI adapters.
    components = [
        ('Xray', '26.9.9', True, 'xray'),
        ('sing-box-extended', sb['version'], True, 'singbox'),
        ('Mieru', '3.38.1', True, 'mieru'),
        ('WebDAV', webdav_lock['commit'][:12], True, 'webdav'),
        ('MasterDNS / StormDNS', '2026.06.13 / 7de2476', True, 'masterdns'),
        ('qWDTT', qwdtt['commit'][:12], True, 'qwdtt'),
        ('TUNA memory runtime', runtime['protocol'], True, 'tuna-runtime'),
        ('TUNA memory JNI', runtime['protocol'], True, 'tuna-memory'),
        # HEV is extracted unchanged from WireTurn.
        ('HEV', 'build ' + digest(binary_dir / 'libhevsocks5.so')[:12], False, 'hevsocks5'),
    ]
    (ASSETS / 'components.json').write_text(json.dumps([
        dict(name=name, version=version, tunaFork=fork, sha256=digest(binary_dir / ('lib' + lib + '.so')))
        for name, version, fork, lib in components], indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('Core manifests generated; native device validation remains pending.')

if __name__ == '__main__':
    generate()
