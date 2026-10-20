"""静态工程检查；不会运行任何数据库脚本。python tests/validate_project.py"""
from pathlib import Path
import json,re,subprocess
from html.parser import HTMLParser
root=Path(__file__).resolve().parents[1]
errors=[]
# 排除 web/node_modules 等：pnpm 符号链接会触发权限错误，且不属于小程序检查范围。
SKIP={'node_modules','dist','target','uploads','.git'}
def project_files(pattern):
 return [p for p in root.rglob(pattern) if not any(part in SKIP for part in p.parts)]
class WxmlParser(HTMLParser):
 def __init__(self,path): super().__init__(convert_charrefs=False);self.stack=[];self.path=path
 def handle_starttag(self,tag,attrs): self.stack.append(tag)
 def handle_startendtag(self,tag,attrs): pass
 def handle_endtag(self,tag):
  if not self.stack or self.stack[-1]!=tag:errors.append(f'{self.path}: 闭合标签不匹配 {tag}')
  else:self.stack.pop()
for p in project_files('*.json'):
 try:json.loads(p.read_text())
 except Exception as e:errors.append(f'{p}: {e}')
for p in project_files('*.js'):
 result=subprocess.run(['node','--check',str(p)],capture_output=True,text=True)
 if result.returncode:errors.append(result.stderr)
for p in project_files('*.wxml'):
 parser=WxmlParser(p.relative_to(root));parser.feed(p.read_text())
 if parser.stack:errors.append(f'{p}: 未闭合标签 {parser.stack}')
 # wx:elif / wx:else 必须紧跟在【同级的】wx:if / wx:elif 之后。
 # 层次化扫描：记录每个 depth 上「上一个兄弟节点」的指令类型。
 src=re.sub(r'<!--.*?-->','',p.read_text(),flags=re.S)
 depth=0; last_at={}
 for m in re.finditer(r'<(/?)([a-zA-Z][\w-]*)((?:"[^"]*"|\'[^\']*\'|[^>"\'])*?)(/?)>',src,flags=re.S):
  closing,attrs,selfclose=m.group(1),m.group(3),m.group(4)
  if closing:
   depth-=1; last_at.pop(depth+1,None); continue
  if re.search(r'\bwx:if\s*=',attrs): directive='if'
  elif re.search(r'\bwx:elif\s*=',attrs): directive='elif'
  elif re.search(r'\bwx:else\b',attrs): directive='else'
  else: directive='other'
  last=last_at.get(depth)
  if directive=='elif' and last not in ('if','elif'):errors.append(f'{p}: wx:elif 与前面的 wx:if 不相邻（中间插入了节点）')
  if directive=='else' and last not in ('if','elif'):errors.append(f'{p}: wx:else 与前面的 wx:if 不相邻（中间插入了节点）')
  # if/elif 可继续接 elif/else；else 与普通节点都中断链
  last_at[depth]='other' if directive in ('other','else') else directive
  if not selfclose:depth+=1
app=json.loads((root/'app.json').read_text());pages=set(app['pages'])
for page in pages:
 for ext in ['.js','.json','.wxml','.wxss']:
  if not (root/(page+ext)).is_file():errors.append(f'缺少已注册页面文件 {page+ext}')
 if 'offline-banner' not in (root/(page+'.wxml')).read_text():errors.append(f'页面未挂全局演示提示 {page}')
# 所有本地静态资源路径和模块 require 均必须存在。
for p in project_files('*.js')+project_files('*.wxml')+project_files('*.wxss'):
 s=p.read_text()
 for asset in re.findall(r'[\'"(](/assets/[^\'"()\s]+)',s):
  if '{{' not in asset and not (root/asset.lstrip('/')).exists():errors.append(f'{p}: 资源不存在 {asset}')
 if p.suffix=='.js':
  for spec in re.findall(r"require\(['\"](\.[^'\"]+)['\"]\)",s):
   target=p.parent/spec
   if not target.exists() and not target.with_suffix('.js').exists():errors.append(f'{p}: require 不存在 {spec}')
 for page in re.findall(r'[\'"`](/pages/[\w-]+/[\w-]+)',s):
  if page.lstrip('/') not in pages:errors.append(f'{p}: 路由未注册 {page}')
 for css in re.findall(r'@import\s+[\'\"]([^\'\"]+)',s):
  if not (p.parent/css).exists():errors.append(f'{p}: 样式导入不存在 {css}')
for p in root.glob('pages/*/*.json'):
 settings=json.loads(p.read_text())
 for component,target in settings.get('usingComponents',{}).items():
  base=root/target.lstrip('/') if target.startswith('/') else p.parent/target
  for ext in ['.js','.json','.wxml','.wxss']:
   if not Path(str(base)+ext).exists():errors.append(f'{p}: 组件缺失 {target+ext}')
# 组件 wxss 不允许标签/ID/属性选择器（微信只允许 class 选择器），否则真机与工具都会告警。
TAGS={'view','text','image','button','input','scroll-view','swiper','swiper-item','navigator','textarea','switch','block','page'}
for p in root.glob('components/*/*.wxss'):
 for i,line in enumerate(p.read_text().splitlines(),1):
  stripped=line.strip()
  # 只看真正的选择器行：含 { 且不是 @ 规则、不是纯属性行
  if '{' not in stripped:continue
  if stripped.startswith('/*') or stripped.startswith('*') or stripped.startswith('@'):continue
  head=stripped.split('{')[0].strip()
  if not head or ':' in head and not head.startswith('.'):continue
  for sel in head.split(','):
   sel=sel.strip()
   if not sel:continue
   if sel.startswith('#') or '[' in sel:errors.append(f'{p}:{i}: 组件 wxss 不允许 ID/属性选择器 → {sel}')
   # 形如 ".a image" 的标签后代选择器：任一段是标签名即违规
   if any(part in TAGS for part in sel.split()):errors.append(f'{p}:{i}: 组件 wxss 不允许标签选择器 → {sel}')
# 按钮文字居中：设了固定 height 却没给 line-height 或 flex 居中时，
# 微信 button 的默认 line-height 会让文字偏上（商品详情、发布闲置都踩过）。
def collect_css(path,seen=None):
 if seen is None:seen=set()
 path=Path(path)
 if path in seen or not path.exists():return ''
 seen.add(path)
 text=path.read_text()
 out=text
 for imp in re.findall(r'@import\s+[\'"]([^\'"]+)',text):
  out+='\n'+collect_css((path.parent/imp),seen)
 return out
for wxml in root.glob('pages/*/*.wxml'):
 css=collect_css(wxml.with_suffix('.wxss'))
 if not css:continue
 for attr in re.findall(r'<button[^>]*class="([^"]*)"',wxml.read_text()):
  for cls in [c for c in attr.split() if c and '{{' not in c]:
   props=';'.join(m.group(1) for m in re.finditer(r'\.'+re.escape(cls)+r'\s*\{([^}]*)\}',css))
   if not props:continue
   m_h=re.search(r'(?<!-)height:([^;]+)',props)
   if not m_h:continue
   height=m_h.group(1).strip()
   has_flex='display:flex' in props
   m_lh=re.search(r'line-height:([^;]+)',props)
   line_height=m_lh.group(1).strip() if m_lh else None
   # 要么 flex 居中，要么 line-height 与 height 完全一致；否则文字会偏移
   if not has_flex and line_height!=height:
    errors.append(f'{wxml.name} .{cls}: height={height} 与 line-height={line_height} 不一致且无 flex 居中，按钮文字会偏移')
# 上传接口必须返回完整 http(s) 地址：小程序把 / 开头的路径当本地资源，会渲染失败。
upload=(root/'backend/src/main/java/com/campuslife/service/UploadService.java').read_text()
assert 'baseUrl + publicPrefix' in upload, '上传接口应返回完整 URL（含 base-url）'
# 注解 SQL XML 必须闭合，禁止动态 ${} 插值和执行 SQL 的配置。
mapper=(root/'backend/src/main/java/com/campuslife/mapper/CampusMapper.java').read_text()
assert '${' not in mapper
assert 'q.like(Post::getTitle,keyword).or().like(Post::getContent,keyword)' in (root/'backend/src/main/java/com/campuslife/service/ForumService.java').read_text()
assert 'body.get("userId")' in (root/'backend/src/main/java/com/campuslife/controller/ForumController.java').read_text()
for statement in re.findall(r'"""(.*?)"""',mapper,re.S):
 if '<script>' in statement:
  import xml.etree.ElementTree as ET
  try: ET.fromstring(statement.strip())
  except Exception as e:errors.append(f'CampusMapper XML: {e}')
sql=(root/'backend/src/main/resources/db/module-messages-academics.sql').read_text()
assert len(re.findall('CREATE TABLE IF NOT EXISTS',sql))==6
assert all('INSERT IGNORE' in insert for insert in re.findall(r'INSERT.*?;',sql,re.S))
assert 'spring.sql.init' not in (root/'backend/src/main/resources/application.yml').read_text()
if errors:
 print('\n'.join(errors));raise SystemExit(1)
print(f'PASS: {len(pages)} 个页面四件套、所有 JS/JSON/WXML、静态资源/路由/组件与 SQL 参数静态检查；未执行数据库脚本。')
