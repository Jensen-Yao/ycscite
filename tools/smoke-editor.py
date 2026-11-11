"""Exercise real Swing editor startup with shared settings and a saved analyzed project.

Requires a desktop-capable Java runtime. This is deliberately separate from headless checks.
"""
from argparse import ArgumentParser
from pathlib import Path
import json
import shutil
import os
import subprocess
import tempfile


def main():
    parser=ArgumentParser()
    parser.add_argument('--cli',type=Path,required=True)
    parser.add_argument('--java',type=Path)
    parser.add_argument('--classpath',type=Path)
    parser.add_argument('--evidence-dir',type=Path)
    args=parser.parse_args();cli=args.cli.resolve();root=Path(__file__).resolve().parents[1]
    doctor=json.loads(subprocess.check_output([str(cli),'--json','doctor'],encoding='utf-8'))
    java=(args.java or Path(doctor['java'])).resolve();classpath=(args.classpath or Path(doctor['classpath'])).resolve()
    with tempfile.TemporaryDirectory(prefix='ycscite-editor-smoke-') as temporary:
        folder=Path(temporary);config=folder/'settings.json';report=folder/'report'
        settings={'format':'records-tsv','language':'en','network':'author','from_year':2012,'to_year':2022,
            'g_index_k':37,'selection':'gindex','top_percent':55,'max_links':3,'similarity':'cosine','pruning':'mst',
            'clustering':'em','labeling':'mi','label_source':'tka','exclude_words':['excluded author'],
            'burst_transition':.71,'keep_isolated':True,'logo':False,'image_scale':1,'charts':['network']}
        config.write_text(json.dumps(settings),encoding='utf-8');source=root/'examples/demo-records.tsv'
        run=subprocess.run([str(cli),'report',str(source),'--config',str(config),'--out',str(report),'--json'],cwd=folder,capture_output=True,encoding='utf-8',timeout=120)
        assert run.returncode==0,run.stderr
        manifest=json.loads(run.stdout)['manifest'];project_file=report/next(p['file'] for p in manifest['project_files'] if p['kind']=='focus')
        project=json.loads(project_file.read_text(encoding='utf-8'));expected=next(n for n in manifest['networks'] if n['kind']=='focus')
        if os.name=='nt':
            modules=subprocess.check_output([str(java),'--list-modules'],encoding='utf-8')
            assert 'jdk.accessibility@' in modules, 'The runtime must include Java accessibility support'
        assert expected['nodes']>0 and project['analysis']['options']['gindexK']==37
        def editor(name,extra,expected_logo=False):
            output=folder/name
            properties=['-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge'] if os.name=='nt' else []
            proc=subprocess.run([str(java),*properties,'-Djava.awt.headless=false','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',str(classpath),'ycscite.Main',*extra,'--smoke-output',str(output)],cwd=folder,capture_output=True,encoding='utf-8',timeout=120)
            assert proc.returncode==0,(proc.stdout,proc.stderr)
            state=json.loads((output/'state.json').read_text(encoding='utf-8'))
            assert state['nodes']==expected['nodes'] and state['edges']==expected['edges'],state
            assert state['modularity']==expected['modularity'],state
            assert state['options']==project['analysis']['options'],(state['options'],project['analysis']['options'])
            back=json.loads((output/'analysis.csl-proj').read_text(encoding='utf-8'))
            assert back['project']==project['project']
            assert back['analysis']['nodes']==project['analysis']['nodes'], [(old['id'],{k:[old.get(k),new.get(k)] for k in old if old.get(k)!=new.get(k)}) for old,new in zip(project['analysis']['nodes'],back['analysis']['nodes']) if old!=new]
            assert back['analysis']['edges']==project['analysis']['edges']
            assert (output/'export-controls.png').is_file()
            assert (output/'editor.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
            assert state['export_logo'] is expected_logo and back['exportLogo'] is expected_logo
            for extension in ['png','svg','pdf']:
                assert (output/('network.'+extension)).read_bytes()==(output/('with-logo' if expected_logo else 'no-logo')/('network.'+extension)).read_bytes()
            assert b'ycscite-brand-footer' not in (output/'no-logo/network.svg').read_bytes()
            assert b'ycscite-brand-footer' in (output/'with-logo/network.svg').read_bytes()

            if args.evidence_dir:
                args.evidence_dir.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(output/'editor.png',args.evidence_dir/(name+'.png'))
                shutil.copyfile(output/'export-controls.png',args.evidence_dir/(name+'-exports.png'))
                shutil.copyfile(output/'state.json',args.evidence_dir/(name+'-state.json'))
            return state
        configured=editor('configured',['--input',str(source),'--config',str(config)])
        restored=editor('restored',['--project',str(project_file),'--lang','en'])
        translated=editor('restored-zh',['--project',str(project_file),'--lang','zh'])
        editor('override-logo',['--project',str(project_file),'--lang','en','--logo'],True)
        assert configured['source_fingerprint']==restored['source_fingerprint']==translated['source_fingerprint']==manifest['analysis_fingerprint']
        print(json.dumps({'ok':True,'checks':['real-swing-window','shared-nondefault-settings','complete-analysis-options',
            'retained-corpus','exact-graph-restore','exact-metrics','bilingual-editor','logo-state-and-variants','accessibility-runtime','ui-screenshot'],'nodes':expected['nodes'],'edges':expected['edges']}))


if __name__=='__main__':main()
