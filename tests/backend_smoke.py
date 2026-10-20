"""启动后端后运行：python tests/backend_smoke.py [http://127.0.0.1:8080/api]
只发 GET 请求；不会执行 SQL，也不会改变关注/消息等业务数据。
"""
import json,sys
from urllib.parse import urlencode
from urllib.request import urlopen
from urllib.error import HTTPError
base=(sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8080/api').rstrip('/')
def get(path,params=None,expected=200):
 url=base+path+('?' + urlencode(params) if params else '')
 try:
  with urlopen(url,timeout=10) as response:status=response.status;body=json.load(response)
 except HTTPError as error:status=error.code;body=json.load(error)
 assert status==expected,(url,status,body)
 assert body['code']==(0 if expected==200 else 400),(url,body)
 return body.get('data')
for params in [None,{'keyword':''},{'keyword':'   '},{'keyword':'字'*65},{'keyword':'台灯','page':'x'}]:
 get('/search',params,400)
for params,limit in [({'keyword':'%','size':999,'page':1},50),({'keyword':'%','size':0,'page':0},1),({'keyword':'台灯'},10),({'keyword':"' OR 1=1 --"},10)]:
 result=get('/search',params)
 assert set(result)=={'list','total'}
 assert isinstance(result['total'],int) and len(result['list'])<=limit
 for post in result['list']:assert {'id','title','content','authorId','authorNickname','liked','followed','createdAt'}<=set(post)
assert isinstance(get('/conversations'),list)
assert isinstance(get('/notifications'),list)
messages=get('/conversations/2/messages',{'size':2})
assert len(messages['list'])<=2 and isinstance(messages['hasMore'],bool)
timetable=get('/timetable')
assert {'termInfo','week','courses'}<=set(timetable)
calendar=get('/calendar')
assert {'termInfo','events'}<=set(calendar)
print('PASS: 后端搜索边界与消息/教学读取冒烟检查；未执行 SQL，未发送写请求。')
