"""后端启动后运行：python tests/learn_smoke.py [API地址] [学校ID]。只读，不发布或修改内容。"""
import json,sys
from urllib.request import build_opener,ProxyHandler
base=(sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8080/api').rstrip('/')
school=sys.argv[2] if len(sys.argv)>2 else '1'
opener=build_opener(ProxyHandler({}))
def get(path):
 with opener.open(base+path,timeout=10) as response:
  assert response.status==200
  body=json.load(response)
 assert body['code']==0,body
 return body['data']
categories=['daike','paper','job','kaoyan','kaogong']
counts=get('/learn/counts?schoolId='+school)
assert set(counts)==set(categories) and all(isinstance(n,int) and n>=0 for n in counts.values())
for category in categories:
 data=get(f'/learn/topics?category={category}&schoolId={school}&size=2')
 assert isinstance(data['list'],list) and len(data['list'])<=2
 for post in data['list']:
  assert post['learnCategory']==category and str(post['schoolId'])==school
  assert {'id','authorId','authorNickname','createdAt','likeCount','commentCount','viewCount'}<=set(post)
 if data['hasMore']:
  next_page=get(f"/learn/topics?category={category}&schoolId={school}&size=2&beforeId={data['nextCursor']}")
  assert not {p['id'] for p in data['list']} & {p['id'] for p in next_page['list']}
assert get('/learn/topics?category=hack&schoolId='+school)['list']==[]
print('PASS: 学习接口、五类数量、学校隔离、详情字段、游标去重和非法分类只读检查。')
