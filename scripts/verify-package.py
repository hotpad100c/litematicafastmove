"""Check the remapped player jar and its nested EXIT before distributing it."""
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile


def verify(root):
    props = dict(line.split('=', 1) for line in
                 (root / 'gradle.properties').read_text(encoding='utf-8').splitlines()
                 if '=' in line and not line.lstrip().startswith('#'))
    jar = root / 'build/libs' / ('litematicafastmove-' + props['version'] + '.jar')
    with ZipFile(jar) as archive:
        assert archive.testzip() is None, 'Corrupt outer jar'
        metadata = json.loads(archive.read('fabric.mod.json'))
        assert metadata['id'] == 'litematicafastmove', metadata
        assert metadata['version'] == props['version'], metadata
        assert metadata['depends']['minecraft'] == '~' + props['minecraft_version'], metadata
        # A class containing named Minecraft references means this is a dev jar.
        renderer = archive.read('ml/mypals/litematicafastmove/mesh/PlacementMeshRenderer.class')
        assert b'net/minecraft/class_' in renderer, 'Expected Fabric intermediary names'
        assert b'net/minecraft/client/Minecraft' not in renderer, 'Found named development classes'
        exits = []
        for nested in metadata.get('jars', []):
            data = archive.read(nested['file'])
            with ZipFile(io.BytesIO(data)) as child:
                assert child.testzip() is None, 'Corrupt nested jar'
                info = json.loads(child.read('fabric.mod.json'))
                if info['id'] == 'emergency_exit':
                    # exit_version is the JitPack tag (0.1.1-1.21.1); the mod itself says 0.1.1+1.21.1.
                    assert info['version'] == props['exit_version'].replace('-', '+', 1), info
                    exits.append((info['version'], hashlib.sha256(data).hexdigest()))
        assert len(exits) == 1, 'Expected exactly one nested EXIT, got ' + repr(exits)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    receipt = {'minecraft': props['minecraft_version'], 'lfm': props['version'],
               'jar': jar.name, 'sha256': digest, 'exit': exits[0][0], 'exit_sha256': exits[0][1]}
    (jar.parent / 'package-verification.json').write_text(
        json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    (jar.parent / 'checksums.sha256').write_text(digest + '  ' + jar.name + '\n', encoding='utf-8')
    print(json.dumps(receipt))


if __name__ == '__main__':
    verify(Path(__file__).resolve().parent.parent)
