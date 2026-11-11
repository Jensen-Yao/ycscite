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
                shutil.copyfile(output/'display-controls.png',args.evidence_dir/(name+'-display.png'))
                shutil.copyfile(output/'state.json',args.evidence_dir/(name+'-state.json'))
            return state
        configured=editor('configured',['--input',str(source),'--config',str(config)])
        restored=editor('restored',['--project',str(project_file),'--lang','en'])
        # The first editor save now contains final coordinates, style and camera for every view.
        editable = folder/'configured'/'analysis.csl-proj'
        portable=json.loads(editable.read_text(encoding='utf-8'))
        assert portable['presentation']['active_view']=='MERGED' and len(portable['presentation']['canvases'])==9
        restored_layout=editor('restored-layout',['--project',str(editable),'--lang','en'])
        assert (folder/'configured/network.png').read_bytes()==(folder/'restored-layout/network.png').read_bytes()
        assert (folder/'configured/network.svg').read_bytes()==(folder/'restored-layout/network.svg').read_bytes()
        inspect=subprocess.run([str(cli),'project','inspect',str(editable)],cwd=folder,capture_output=True,encoding='utf-8',timeout=30)
        assert inspect.returncode==0,inspect.stderr
        metadata=json.loads(inspect.stdout)['result'];assert metadata['has_saved_views'] and metadata['active_view']=='MERGED'
        headless=folder/'saved-layout-export'
        export=subprocess.run([str(cli),'project','export',str(editable),'--out',str(headless),'--formats','png,svg,pdf','--no-logo'],cwd=folder,capture_output=True,encoding='utf-8',timeout=120)
        assert export.returncode==0,export.stderr
        assert json.loads(export.stdout)['result']['manifest']['restored_saved_view']
        for filename in ['network.png','report.pdf']:
            assert (headless/filename).read_bytes()==(folder/'configured'/('network.pdf' if filename=='report.pdf' else filename)).read_bytes(),filename
        vector=folder/'saved-layout-vector'
        vector_export=subprocess.run([str(cli),'project','export',str(editable),'--out',str(vector),'--formats','svg','--width','2400','--height','1500','--no-logo'],cwd=folder,capture_output=True,encoding='utf-8',timeout=60)
        assert vector_export.returncode==0,vector_export.stderr
        assert (vector/'network.svg').read_bytes()==(folder/'configured/network.svg').read_bytes()
        changed=json.loads(editable.read_text(encoding='utf-8'))
        changed['presentation']['sidebar_tab']=2
        view=changed['presentation']['canvases']['MERGED']
        ids=list(view['positions']);view['positions'][ids[0]]=[230.25,315.75];view['hidden']=[ids[-1]]
        view['display'].update(theme='SEPIA',font_size=17,label_angle=30,node_scale=1.6,curved_links=True,link_opacity=.6)
        view['camera']={'scale':1.3,'center_x':500.0,'center_y':300.0}
        edited=folder/'manually-adjusted.csl-proj';edited.write_text(json.dumps(changed,ensure_ascii=False),encoding='utf-8')
        editor('manual-layout',['--project',str(edited)])
        saved_manual=json.loads((folder/'manual-layout/analysis.csl-proj').read_text(encoding='utf-8'))
        assert saved_manual['presentation']['language']=='en' and saved_manual['presentation']['sidebar_tab']==2
        saved_view=saved_manual['presentation']['canvases']['MERGED']
        assert saved_view['positions']==view['positions'] and saved_view['hidden']==view['hidden'] and saved_view['display']==view['display'], {key:[view[key],saved_view[key]] for key in ['positions','hidden','display'] if view[key]!=saved_view[key]}
        manually_exported=folder/'manual-headless'
        manual_run=subprocess.run([str(cli),'project','export',str(edited),'--out',str(manually_exported),'--formats','png,pdf','--no-logo'],cwd=folder,capture_output=True,encoding='utf-8',timeout=90)
        assert manual_run.returncode==0,manual_run.stderr
        for filename in ['network.png','report.pdf']:
            assert (manually_exported/filename).read_bytes()==(folder/'manual-layout'/('network.pdf' if filename=='report.pdf' else filename)).read_bytes(),filename
        legacy_out=folder/'legacy-saved-visuals'
        legacy_export=subprocess.run([str(cli),'--json','engine','open',str(edited),'--png','--pdf','--out',str(legacy_out)],cwd=folder,capture_output=True,encoding='utf-8',timeout=90)
        assert legacy_export.returncode==0 and json.loads(legacy_export.stdout)['ok'],legacy_export.stderr
        for filename in ['network.png','report.pdf']:
            assert (legacy_out/filename).read_bytes()==(manually_exported/filename).read_bytes(),filename
        pending_settings=dict(settings,g_index_k=78)
        pending_config=folder/'pending-settings.json';pending_config.write_text(json.dumps(pending_settings),encoding='utf-8')
        pending_result=editor('unapplied-options',['--project',str(project_file),'--lang','en','--smoke-edit-settings',str(pending_config)])
        assert pending_result['options']['gindexK']==37 and pending_result['pending_options']['gindexK']==78
        translated=editor('restored-zh',['--project',str(project_file),'--lang','zh'])
        editor('override-logo',['--project',str(project_file),'--lang','en','--logo'],True)
        assert configured['source_fingerprint']==restored['source_fingerprint']==translated['source_fingerprint']==manifest['analysis_fingerprint']
        print(json.dumps({'ok':True,'checks':['real-swing-window','shared-nondefault-settings','complete-analysis-options',
            'retained-corpus','exact-graph-restore','exact-metrics','bilingual-editor','logo-state-and-variants','saved-layout-pixel-roundtrip','headless-project-reexport','unapplied-options-isolation','accessibility-runtime','ui-screenshot'],'nodes':expected['nodes'],'edges':expected['edges']}))


if __name__=='__main__':main()
