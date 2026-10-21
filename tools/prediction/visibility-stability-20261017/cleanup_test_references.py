from pathlib import Path

neo = Path(__file__).resolve().parents[3]
for root in (neo, neo.parent / 'voxyserverside'):
    for path in (root / 'src/test/java/dev/xantha/vss/client/prediction').glob('*.java'):
        text = path.read_text(encoding='utf-8-sig')
        if 'PredictionPreparedPassLocalTest.coverage()' not in text:
            continue
        backup = root / 'tools/prediction/visibility-stability-20261017/before-files' / path.relative_to(root)
        if not backup.exists():
            backup.parent.mkdir(parents=True, exist_ok=True)
            backup.write_text(text, encoding='utf-8', newline='\n')
        if path.name == 'PredictionPreparedPassBenchmark.java':
            text = text.replace(',"local-coverage"', '')
            start = text.index('                if(scenario.equals("local-coverage")) {')
            end = text.index('                payloads.add(mesh);', start)
            text = text[:start] + text[end:]
            start = text.index('            var coverageIndex=new PredictionExactCoverageIndex();')
            end = text.index('            long[][] times=', start)
            text = text[:start] + text[end:]
            start = text.index('                if(scenario.equals("local-coverage")) {')
            end = text.index('                for(int order=0;', start)
            text = text[:start] + text[end:]
            text = text.replace('/** Production range/filter paths', '/** Production range/cache paths')
        text = text.replace('    @AfterEach void reset() throws Exception { PredictionPreparedPassLocalTest.coverage().clear(); }\n', '')
        text = text.replace('    @AfterEach void clear() throws Exception { PredictionPreparedPassLocalTest.coverage().clear(); }\n', '')
        text = text.replace('PredictionPreparedPassLocalTest.coverage().clear();', '')
        text = '\n'.join(line.rstrip() for line in text.splitlines()) + '\n'
        path.write_text(text, encoding='utf-8', newline='\n')
        print(root.name, path.name)
