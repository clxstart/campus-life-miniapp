const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const { categories, findCategory } = require('../utils/learn')
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b}); return {promise,resolve,reject} }
function pageHarness(file = 'pages/learn/learn.js', api = {}) {
  let config, schoolId = 1, previousPages = []
  const calls = [], timers = []
  const mockedApi = { currentUserId:()=>7, uploadImages:async()=>[], assetUrl:value=>value||'', getLearnTopics:async()=>({list:[],total:0,hasMore:false,nextCursor:''}), getLearnCounts:async()=>({}), ...api }
  const school = {getSelectedId:()=>schoolId,getSelectedName:()=>schoolId===1?'测试学校':'另一所测试学校',getSelectedLocations:async()=>['图书馆']}
  const popup = {toast:(_,message)=>calls.push(['toast',message]),modal:(_,options)=>calls.push(['modal',options]),hide:()=>{}}
  const wx = new Proxy({}, {get:(_,name)=>(arg)=>calls.push([name,arg])})
  const context = {Page(value){config=value},wx,getCurrentPages:()=>previousPages,setTimeout:fn=>{timers.push(fn);return timers.length},clearTimeout(){},
    require(spec){if(spec.endsWith('/api'))return mockedApi;if(spec.endsWith('/school'))return school;if(spec.endsWith('/navigation'))return {getNavigationMetrics:()=>({statusBarHeight:20,navBarHeight:44,capsuleReservedRight:100})};if(spec.endsWith('/ui-popup-helper'))return popup;return require(path.resolve(root,path.dirname(file),spec))}}
  vm.runInNewContext(fs.readFileSync(path.join(root,file),'utf8'),context)
  const instance={...config,data:JSON.parse(JSON.stringify(config.data)),setData(patch){Object.assign(this.data,patch)}}
  return {page:instance,calls,timers,setSchool:id=>{schoolId=id},setPages:pages=>{previousPages=pages}}
}
function loadApi(request) {
  const context={module:{exports:{}},setTimeout:fn=>fn(),wx:{request,getStorageSync:()=>'',removeStorageSync:()=>{}},require(spec){if(spec==='./mock-data')return {};if(spec==='./data-mode')return {setDemo(){},connection(){}};throw Error(spec)}}
  vm.runInNewContext(fs.readFileSync(path.join(root,'utils/api.js'),'utf8'),context)
  return context.module.exports
}
const event = key => ({currentTarget:{dataset:{key}}})
const result = (ids,more=false,cursor='') => ({list:ids.map(id=>({id})),total:20,hasMore:more,nextCursor:cursor})
test('五个分类一致，非法入口回到有效分类',()=>{assert.deepEqual(categories.map(c=>c.key),['daike','paper','job','kaoyan','kaogong']);assert.equal(findCategory('hack').key,'paper')})
test('学习列表请求携带学校与游标，数量参数限制到边界',async()=>{
  let request;const api=loadApi(options=>{request=options;options.success({statusCode:200,data:{code:0,data:result([])}})})
  for(const [size,expected]of [[999,50],[0,1],[-3,1],[NaN,10],[Infinity,10]]){
    await api.getLearnTopics({category:'paper',schoolId:2,beforeId:'90',size});const u=new URL(request.url)
    assert.equal(u.searchParams.get('schoolId'),'2');assert.equal(u.searchParams.get('beforeId'),'90');assert.equal(Number(u.searchParams.get('size')),expected)
  }
})
test('断网读接口回落空列表与五类计数',async()=>{
  const api=loadApi(options=>options.fail(new Error('offline'))),res=await api.getLearnTopics()
  assert.equal(res.offline,true);assert.equal(res.list.length,0);assert.equal(res.hasMore,false)
  const counts=await api.getLearnCounts(2);assert.equal(Object.keys(counts).length,5);assert.ok(Object.values(counts).every(n=>n===0))
})
test('服务器拒绝不变成离线数据；发布、点赞、评论网络失败均抛错',async()=>{
  const denied=loadApi(options=>options.success({statusCode:400,data:{code:400,msg:'拒绝'}}))
  await assert.rejects(denied.getLearnTopics(),/拒绝/);await assert.rejects(denied.getLearnCounts(),/拒绝/)
  const api=loadApi(options=>options.fail(new Error('offline')))
  await assert.rejects(api.publishPost({learnCategory:'paper'}),/offline/);await assert.rejects(api.likePost(1),/offline/);await assert.rejects(api.addComment(1,'test'),/offline/)
})
test('复用帖子接口，学习发布请求带选中学校、分类与真实用户',async()=>{
  let body;const api=loadApi(options=>{body=options.data;options.success({statusCode:200,data:{code:0,data:{id:9}}})})
  await api.publishPost({title:'test',content:'content',learnCategory:'job',schoolId:2,crossSchool:false},7)
  assert.equal(body.userId,7);assert.equal(body.schoolId,2);assert.equal(body.learnCategory,'job');assert.equal(body.tag,'学习');assert.equal(body.crossSchool,false)
})
test('快速切换分类时旧响应不能覆盖新列表',async()=>{
  const a=deferred(),b=deferred(),{page}=pageHarness(undefined,{getLearnTopics:q=>q.category==='paper'?a.promise:b.promise})
  page.onLoad({category:'paper'});page.onTabTap(event('job'));b.resolve(result([2]));await tick();a.resolve(result([1]));await tick()
  assert.equal(page.data.category,'job');assert.equal(page.data.posts[0].id,2);assert.equal(page.data.loading,false)
})
test('上拉透传上一页游标，重复触底只加载一次，追加去重',async()=>{
  const next=deferred();let calls=0
  const {page}=pageHarness(undefined,{getLearnTopics:async q=>{calls++;if(!q.beforeId)return result([10,9],true,'9');assert.equal(q.beforeId,'9');return next.promise}})
  page.onLoad({});await tick();const promise=page.onReachBottom();page.onReachBottom();assert.equal(calls,2)
  next.resolve(result([9,8],false,''));await promise;assert.deepEqual(Array.from(page.data.posts,p=>p.id),[10,9,8]);assert.equal(page.data.hasMore,false)
})
test('翻页断网保留已有内容和游标，再次点击能重试',async()=>{
  let offline=true
  const {page}=pageHarness(undefined,{getLearnTopics:async q=>!q.beforeId?result([10],true,'10'):offline?{list:[],offline:true}:result([9])})
  page.onLoad({});await tick();await page.onReachBottom();assert.equal(page.data.posts.length,1);assert.equal(page.data.cursor,'10');assert.equal(page.data.offline,true)
  offline=false;await page.retry();assert.equal(page.data.posts.length,2);assert.equal(page.data.offline,false)
})
test('切学校后丢弃原学校的迟到响应',async()=>{
  const a=deferred(),{page,setSchool}=pageHarness(undefined,{getLearnTopics:async q=>q.schoolId===1?a.promise:result([2])})
  page.onLoad({});setSchool(2);page.onShow();await tick();a.resolve(result([1]));await tick();assert.equal(page.data.posts[0].id,2);assert.equal(page.data.schoolId,2)
})
test('空列表、离线、服务器错误的界面状态互相区分',async()=>{
  const {page}=pageHarness(undefined,{getLearnTopics:async()=>({list:[],total:0,offline:true})});page.onLoad({});await tick();assert.equal(page.data.offline,true);assert.equal(page.data.posts.length,0)
  const denied=pageHarness(undefined,{getLearnTopics:async()=>{throw Error('校验失败')}}).page;denied.onLoad({});await tick();assert.equal(denied.data.error,'校验失败');assert.equal(denied.data.offline,false)
})
test('下拉刷新结束停止动画，页面卸载后不再更新',async()=>{
  const a=deferred(),{page,calls}=pageHarness(undefined,{getLearnTopics:()=>a.promise});page.onLoad({});const refresh=page.onPullDownRefresh();page.onUnload();const snapshot=JSON.stringify(page.data)
  a.resolve(result([1]));await refresh;assert.equal(JSON.stringify(page.data),snapshot);assert.ok(calls.some(c=>c[0]==='stopPullDownRefresh'))
})
test('学习列表的发帖、详情、助手入口路由完整',()=>{
  const {page,calls}=pageHarness();page.data.category='kaoyan';page.openPublish();page.openAiChat();page.openDetail({currentTarget:{dataset:{id:7}}})
  assert.ok(calls.some(c=>c[1].url==='/pages/learn-publish/learn-publish?category=kaoyan'));assert.ok(calls.some(c=>c[1].url==='/pages/post-detail/post-detail?id=7'));assert.ok(calls.some(c=>c[1].url==='/pages/ai-chat/ai-chat'))
})
test('发布页预选当前板块，编辑中可更换',()=>{
  const {page}=pageHarness('pages/learn-publish/learn-publish.js');page.onLoad({category:'kaogong'});assert.equal(page.data.category,'kaogong');page.chooseCategory(event('paper'));assert.equal(page.data.category,'paper')
})
test('空标题、空正文、未选学校都不提交',async()=>{
  let writes=0;const {page,setSchool}=pageHarness('pages/learn-publish/learn-publish.js',{publishPost:async()=>{writes++}})
  page.onLoad({});await page.submit();page.data.title='标题';await page.submit();page.data.content='正文';setSchool(null);await page.submit();assert.equal(writes,0)
})
test('上传失败或发布失败保留图片和输入，不跳转',async()=>{
  for(const atUpload of [true,false]){
    let writes=0;const {page,calls}=pageHarness('pages/learn-publish/learn-publish.js',{uploadImages:async()=>{if(atUpload)throw Error('上传失败');return ['https://test/img.jpg']},publishPost:async()=>{writes++;throw Error('发布失败')}})
    page.onLoad({});page.setData({title:'标题',content:'正文',media:['wxfile://tmp/image.jpg']});await page.submit();assert.equal(writes,atUpload?0:1);assert.equal(page.data.title,'标题');assert.equal(page.data.media.length,1);assert.equal(page.data.submitting,false);assert.ok(!calls.some(c=>c[0]==='redirectTo'))
  }
})
test('成功发布使用提交时学校；返回列表切到实际发布板块；重复点击不重复写入',async()=>{
  const pending=deferred();let writes=0,payload,user
  const env=pageHarness('pages/learn-publish/learn-publish.js',{publishPost:async(p,u)=>{writes++;payload=p;user=u;await pending.promise}}),{page,timers,calls}=env
  page.onLoad({category:'job'});page.setData({title:'标题',content:'正文'});env.setSchool(2)
  const task=page.submit();await tick();await page.submit();assert.equal(writes,1);pending.resolve();await task;await page.submit();assert.equal(writes,1)
  assert.equal(payload.schoolId,2);assert.equal(payload.learnCategory,'job');assert.equal(user,7)
  let previousPatch;const previous={route:'pages/learn/learn',setData:p=>previousPatch=p};env.setPages([previous,page]);timers[0]();assert.equal(previousPatch.category,'job');assert.equal(previous._needReload,true);assert.ok(calls.some(c=>c[0]==='navigateBack'))
})
test('首页整卡 bindtap 和内部 catchtap 保持不同类型，学习好物仍到市场',()=>{
  const wxml=fs.readFileSync(path.join(root,'pages/index/index.wxml'),'utf8')
  assert.match(wxml,/<view class="thesis-card" bindtap="openLearnZone"/)
  assert.equal((wxml.match(/class="thesis-action" catchtap="openFeature"/g)||[]).length,3)
  const js=fs.readFileSync(path.join(root,'pages/index/index.js'),'utf8');assert.match(js,/'学习好物': \{ url: '\/pages\/market-list\/market-list/);assert.match(js,/openLearnZone\(\)\s*\{\s*wx.navigateTo\(\{ url: '\/pages\/learn\/learn\?category=daike'/)
})
