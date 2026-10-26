// 运行独立预览的真实脚本；DOM 行为检查，不冒充微信或浏览器像素验收。
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path')
let document
class Element{
 constructor(tag,text=''){this.tag=tag;this.tagName=tag.toUpperCase();this.text=text;this.children=[];this.attrs={};this.style={};this.events={};this.dataset={};this.value='';this.selectionStart=0;this.offsetLeft=0}
 append(...children){for(const child of children){child.parent=this;this.children.push(child)}}
 set className(v){this.attrs.class=v}get className(){return this.attrs.class||''}
 set textContent(v){this.text=v;this.children=[]}get textContent(){return this.text+this.children.map(c=>c.textContent).join('')}
 set innerHTML(v){this.text=v;this.children=[]}get innerHTML(){return this.text}
 setAttribute(k,v){this.attrs[k]=v;if(k.startsWith('data-'))this.dataset[k.slice(5)]=v}
 set src(v){this.attrs.src=v}get src(){return this.attrs.src}
 addEventListener(k,fn){this.events[k]=fn}
 focus(){document.activeElement=this}setSelectionRange(n){this.selectionStart=n}
 remove(){if(this.parent)this.parent.children=this.parent.children.filter(c=>c!==this)}
 querySelector(s){return this.querySelectorAll(s)[0]||null}
 querySelectorAll(s){const out=[];const visit=n=>{if((s[0]==='.'&&n.className.split(' ').includes(s.slice(1)))||(s[0]==='#'&&n.attrs.id===s.slice(1))||n.tag===s)out.push(n);n.children.forEach(visit)};this.children.forEach(visit);return out}
}
const body=new Element('body'),app=new Element('div'),modal=new Element('div'),styles=new Element('style');app.attrs.id='app';modal.attrs.id='modal';styles.attrs.id='page-styles';body.append(app,modal,styles)
document={activeElement:null,createElement:t=>new Element(t),createTextNode:t=>new Element('#text',t),querySelector:s=>body.querySelector(s)}
const html=fs.readFileSync(path.resolve(__dirname,'../learn-preview.html'),'utf8'),source=html.match(/<script>([\s\S]*)<\/script>/)[1]
const context={document,console,URL,setTimeout:fn=>setTimeout(fn,0),clearTimeout,scrollTo(){}};context.window=context
vm.runInNewContext(source,context)
const tick=()=>new Promise(r=>setTimeout(r,20)),evt=()=>({stopPropagation(){}})
;(async()=>{
 await tick();assert.equal(app.querySelectorAll('.post-card').length,1);assert.ok(app.querySelectorAll('img').every(i=>i.src.startsWith('data:image/')))
 for(const key of ['daike','paper','job','kaoyan','kaogong']){context.getPreviewApp().onTabTap({currentTarget:{dataset:{key}}});await tick();assert.equal(app.querySelectorAll('.post-card').length,1)}
 context.getPreviewApp().openPublish();assert.equal(app.querySelectorAll('.editor-page').length,1)
 context.getPreviewApp().chooseCategory({currentTarget:{dataset:{key:'job'}}})
 const title=app.querySelector('.title-input');title.value='独立预览发布验收';title.events.input(evt())
 const text=app.querySelector('.content-input');text.value='在预览里输入的学习分享正文';text.events.input(evt())
 await context.getPreviewApp().submit();await tick();assert.equal(app.querySelectorAll('.editor-page').length,0);assert.equal(app.querySelector('.post-title').textContent,'独立预览发布验收')
 context.switchSchool();await tick();assert.equal(app.querySelectorAll('.post-card').length,1)
 for(const mode of ['empty','offline','error']){context.setMode(mode);await tick();assert.equal(app.querySelectorAll(mode==='error'?'.error-card':'.empty-card').length,1)}
 console.log('PASS: 预览脚本、内嵌图片、五类切换、输入与发布回显、切校及空/离线/错误状态 DOM 验证。')
})().catch(e=>{console.error(e);process.exitCode=1})
