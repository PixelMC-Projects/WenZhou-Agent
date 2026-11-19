import React, { useRef, useState } from 'react';
import { EditorContent, useEditor, useEditorState } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { ArrowClockwise, ArrowCounterClockwise, LinkSimple, ListBullets, TextB, TextHTwo, TextHThree, TextT } from '@phosphor-icons/react';
import { agreementDocument, isAgreementLink } from './agreementDocument.js';

const extensions = [StarterKit.configure({
  heading:{levels:[2,3]}, link:{openOnClick:false, autolink:false, linkOnPaste:false, isAllowedUri:isAgreementLink},
  blockquote:false, code:false, codeBlock:false, horizontalRule:false, italic:false,
  orderedList:false, strike:false, underline:false, trailingNode:false,
})];

export default React.memo(function AgreementEditor({label,field,value,onChange,readOnly}) {
  const [initialContent] = useState(() => agreementDocument(value));
  const initialValue = useRef(value);
  const initialDoc = useRef(null);
  const [link, setLink] = useState(null);
  const editor = useEditor({
    extensions, editable:!readOnly, content:initialContent, enableInputRules:false, enablePasteRules:false,
    editorProps:{attributes:{class:'wza-policy-body', role:'textbox', 'aria-label':label, 'aria-multiline':'true'}},
    onUpdate:({editor})=>onChange(field, initialDoc.current?.eq(editor.state.doc) ? initialValue.current : editor.getJSON()),
  });
  React.useEffect(() => {
    editor?.setEditable(!readOnly, false);
    if (readOnly) setLink(null);
  }, [editor, readOnly]);
  if (editor && !initialDoc.current) initialDoc.current = editor.state.doc;
  const state = useEditorState({editor, selector:({editor:e})=>({
    heading:e?.getAttributes('heading').level, bold:e?.isActive('bold'), list:e?.isActive('bulletList'),
    link:e?.isActive('link'), undo:e?.can().undo(), redo:e?.can().redo(),
  })});
  function keepSelection(event) {
    event.preventDefault();
    const selection = window.getSelection();
    const view = editor.view;
    if (!selection?.rangeCount || !view.dom.contains(selection.anchorNode) || !view.dom.contains(selection.focusNode)) return;
    const range = selection.getRangeAt(0);
    const from = view.posAtDOM(range.startContainer,range.startOffset);
    const to = view.posAtDOM(range.endContainer,range.endOffset);
    if (from !== editor.state.selection.from || to !== editor.state.selection.to) {
      editor.commands.setTextSelection({from,to});
    }
  }
  function tool(title,Icon,action,pressed=false,disabled=false) {
    return <button type="button" title={title} aria-label={title} aria-pressed={pressed} disabled={!editor || readOnly || disabled}
      onMouseDown={keepSelection} onClick={action}><Icon size={16}/></button>;
  }
  function editLink() {
    if (editor.isActive('link')) editor.commands.extendMarkRange('link');
    const {from,to} = editor.state.selection;
    setLink({from,to,url:editor.getAttributes('link').href || ''});
  }
  function applyLink(remove=false) {
    const href = link.url.trim();
    if (!remove && !isAgreementLink(href)) return;
    const chain = editor.chain().focus().setTextSelection({from:link.from,to:link.to});
    if (remove) chain.unsetLink();
    else if (link.from === link.to) chain.insertContent({type:'text', text:href, marks:[{type:'link',attrs:{href}}]});
    else chain.setLink({href});
    chain.run();
    setLink(null);
  }
  return <section className="wza-policy-field ph-article-editor" data-field={field} aria-label={label}>
    <div className="wza-field-label">{label}</div>
    <div className="ph-writing-tools" role="toolbar" aria-label={label+'格式'}>
      {tool('正文',TextT,()=>editor.chain().focus().setParagraph().run(),!state?.heading && !state?.list)}
      {tool('标题',TextHTwo,()=>editor.chain().focus().toggleHeading({level:2}).run(),state?.heading===2)}
      {tool('小标题',TextHThree,()=>editor.chain().focus().toggleHeading({level:3}).run(),state?.heading===3)}
      {tool('加粗',TextB,()=>editor.chain().focus().toggleBold().run(),state?.bold)}
      {tool('项目列表',ListBullets,()=>editor.chain().focus().toggleBulletList().run(),state?.list)}
      {tool('链接',LinkSimple,editLink,state?.link)}
      <span className="ph-tool-divider"/>
      {tool('撤销',ArrowCounterClockwise,()=>editor.chain().focus().undo().run(),false,!state?.undo)}
      {tool('重做',ArrowClockwise,()=>editor.chain().focus().redo().run(),false,!state?.redo)}
    </div>
    {link && <form className="wza-policy-link" onSubmit={event=>{event.preventDefault();applyLink();}}
      onKeyDown={event=>{if(event.key==='Escape'){event.preventDefault();setLink(null);editor.commands.focus();}}}>
      <label>链接地址<input autoFocus type="url" placeholder="https://" value={link.url}
        onChange={event=>setLink({...link,url:event.target.value})}/></label>
      <button type="submit" className="button secondary" disabled={!isAgreementLink(link.url.trim())}>应用</button>
      {state.link && <button type="button" className="button quiet" onClick={()=>applyLink(true)}>移除链接</button>}
      <button type="button" className="button quiet" onClick={()=>{setLink(null);editor.commands.focus();}}>取消</button>
    </form>}
    <EditorContent editor={editor} className="wza-policy-paper" onFocus={()=>setLink(null)}/>
  </section>;
});
