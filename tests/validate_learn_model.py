"""验证学习专区示例 SQL、按校过滤与游标边界；这是模型测试，不替代 MySQL 联调。"""
from pathlib import Path
import sqlite3,re,itertools,json
root=Path(__file__).resolve().parents[1]
db=sqlite3.connect(':memory:');db.create_function('NOW',0,lambda:'2026-10-17 10:00:00')
db.executescript('CREATE TABLE school(id INTEGER);INSERT INTO school VALUES(1),(2);CREATE TABLE user(id INTEGER);INSERT INTO user VALUES(1),(2),(3);CREATE TABLE post(id INTEGER PRIMARY KEY,user_id INTEGER,title TEXT,content TEXT,image TEXT,tag TEXT,learn_category TEXT,school_id INTEGER,status INTEGER,like_count INTEGER,comment_count INTEGER,view_count INTEGER,created_at TEXT,updated_at TEXT);')
# 必需迁移不能偷偷填充用户内容，空库由用户正常发布。
migration=(root/'backend/src/main/resources/db/module-learn.sql').read_text();assert 'INSERT INTO post' not in migration
assert db.execute('SELECT COUNT(*) FROM post').fetchone()[0]==0
optional=(root/'backend/src/main/resources/db/module-learn-demo.sql').read_text();seeds=re.findall(r'INSERT INTO post .*?;',optional,re.S)
for _ in range(2):
 for seed in seeds:db.execute(seed)
counts=dict(db.execute('SELECT learn_category,COUNT(*) FROM post GROUP BY learn_category'))
assert counts=={'daike':1,'paper':2,'job':1,'kaoyan':1,'kaogong':1}
assert sum(counts.values())==len(seeds)==db.execute('SELECT COUNT(*) FROM post').fetchone()[0]
codes=list(counts)
for cat,school,status in itertools.product(codes+['hack',None],[None,1,2],[0,1]):db.execute('INSERT INTO post(learn_category,school_id,status)VALUES(?,?,?)',(cat,school,status))
rows=db.execute('SELECT id,learn_category,school_id,status FROM post ORDER BY id DESC').fetchall();checks=0
for category,school,limit,before in itertools.product(codes+['hack',None],[1,2,3],[0,1,2,10,50,999],[None,1,12,999]):
 size=min(50,max(1,limit))
 expected=[r for r in rows if category in codes and r[1]==category and r[2]==school and r[3]==1 and (before is None or r[0]<before)]
 actual=[] if category not in codes else db.execute('SELECT id,learn_category,school_id,status FROM post WHERE status=1 AND learn_category=? AND school_id=? AND (? IS NULL OR id<?) ORDER BY id DESC LIMIT ?',(category,school,before,before,size+1)).fetchall()
 assert actual[:size]==expected[:size]
 assert (len(actual)>size)==(len(expected)>size)
 assert all(row[2]==school for row in actual)
 checks+=1
# 并发新增与删除已读帖子，游标继续读取仍覆盖原始未读内容。
for category,school,size in itertools.product(codes,[1,2],[1,2,3,10]):
 db.execute('SAVEPOINT scenario')
 original=[r[0] for r in db.execute('SELECT id FROM post WHERE status=1 AND learn_category=? AND school_id=? ORDER BY id DESC',(category,school))]
 seen=original[:size];cursor=seen[-1] if seen else None
 db.execute('INSERT INTO post(learn_category,school_id,status)VALUES(?,?,1)',(category,school))
 if seen:db.execute('DELETE FROM post WHERE id=?',(seen[0],))
 if cursor:
  while True:
   batch=[r[0] for r in db.execute('SELECT id FROM post WHERE status=1 AND learn_category=? AND school_id=? AND id<? ORDER BY id DESC LIMIT ?',(category,school,cursor,size))]
   if not batch:break
   seen+=batch;cursor=batch[-1]
 assert seen==original and len(seen)==len(set(seen))
 db.execute('ROLLBACK TO scenario');db.execute('RELEASE scenario');checks+=1
service=(root/'backend/src/main/java/com/campuslife/service/ForumService.java').read_text()
actual_codes=re.findall(r'"(\w+)"',re.search(r'LEARN_CATEGORIES=Set.of\((.*?)\)',service)[1]);assert set(actual_codes)==set(codes)
learn=service[service.index(' public Map<String,Object> learnTopics'):service.index(' public Map<String,Long> learnCounts')]
assert '.eq(Post::getSchoolId,selectedSchoolId)' in learn and '.or().isNull' not in learn
assert 'wrapper.lt(Post::getId,beforeId)' in learn
for page in ['learn','learn-publish']:
 css=(root/f'pages/{page}/{page}.wxss').read_text();assert 'linear-gradient' not in css and 'box-sizing:content-box' in css
 for selector in re.findall(r'([^{}]+)\{',re.sub(r'/\*.*?\*/','',css,flags=re.S)):
  assert all(part.strip().startswith('.') for part in selector.split(',')),selector
print(f'PASS: {checks} 组分类/学校/数量/游标与并发增删场景；可选示例 SQL 重复执行、数量反算和样式约束通过。')
