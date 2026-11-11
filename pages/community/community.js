const popup = require('../../utils/ui-popup-helper')
const api = require('../../utils/api')
const { formatTime } = require('../../utils/format-time')
const tabs = ['关注','推荐','学习','生活','求助','二手','社团']
const tabMap = { 关注:{scope:'followed'}, 推荐:{tag:''}, 学习:{tag:'学习'}, 生活:{tag:'校园日常'}, 求助:{tag:'求助'}, 二手:{tag:'二手闲置'}, 社团:{tag:'社团活动'} }
Page({
  data:{statusBarHeight:20,navBarHeight:44,tabs,activeTab:1,postScope:'local',posts:[],page:1,size:6,total:0,loading:false,offline:false,uiPopup:null},
  onPopupConfirm(e){this._popupConfirm&&this._popupConfirm(e)},
  onPopupCancel(){this._popupCancel&&this._popupCancel()},
  onPopupAction(e){this._popupAction&&this._popupAction(e)},
  onPopupInput(e){this._popupInput&&this._popupInput(e)},
  onPopupClose(){popup.hide(this)},
  onLoad(q){this._alive=true;this.initNavigation();if(q&&q.tab!==undefined&&tabs[Number(q.tab)]){this.setData({activeTab:Number(q.tab)})}this.loadPosts(true)},
  onShow(){ if(this._needReload){ this._needReload=false; this.loadPosts(true) } },
  // 页面卸载后异步请求可能才回来，标记一下避免对已销毁页面 setData
  onUnload(){ this._alive=false },
  // 论坛日榜 / 周榜入口：tab 行右侧小胶囊（不新增第 8 个 tab，否则 tab 行会折行）
  openRank(){ wx.navigateTo({ url: '/pages/forum-rank/forum-rank?type=daily' }) },
  initNavigation(){try{const w=wx.getWindowInfo?wx.getWindowInfo():wx.getSystemInfoSync();this.setData({statusBarHeight:w.statusBarHeight||20})}catch(e){}},
  loadPosts(reset=false){
    if(this.data.loading)return
    const page=reset?1:this.data.page+1
    this.setData({loading:true})
    const q = tabMap[tabs[this.data.activeTab]] || {}
    api.getForumPosts({tag:q.tag||'',scope:q.scope||'',postScope:this.data.postScope,page,size:this.data.size}).then(res=>{
      if(!this._alive)return
      const raw=res.list||[]
      const list=raw.map(x=>({...x,avatarText:(x.authorNickname||'同')[0],createdAt:formatTime(x.createdAt)}))
      // 按 id 去重后再合并：
      // 翻页边界重复、离线兜底与后端数据叠加、同一条帖子被两次拉取，
      // 都会让列表里出现两条一模一样的帖子。这里统一兜底。
      const merged=reset?list:this.data.posts.concat(list)
      const seen=new Set(), dedup=[]
      for(const item of merged){
        const key=String(item.id)
        if(seen.has(key))continue
        seen.add(key); dedup.push(item)
      }
      this.setData({posts:dedup,page,total:res.total||0,offline:!!res.offline})
    }).catch(()=>{if(this._alive)this.setData({offline:true})}).finally(()=>{if(this._alive)this.setData({loading:false})})
  },
  onTabTap(e){const activeTab=Number(e.currentTarget.dataset.index);if(activeTab===this.data.activeTab)return;this.setData({activeTab,posts:[],page:1});this.loadPosts(true)},
  // 本校/全部切换：清空列表重新拉取，避免两种范围的帖子混在一起
  switchPostScope(e){
    const postScope=e.currentTarget.dataset.scope
    if(!postScope||postScope===this.data.postScope)return
    this.setData({postScope,posts:[],page:1})
    this.loadPosts(true)
  },
  onPullDownRefresh(){this.loadPosts(true);setTimeout(()=>wx.stopPullDownRefresh(),500)},
  onReachBottom(){if(this.data.posts.length<this.data.total)this.loadPosts(false)},
  openPublish(){wx.navigateTo({url:'/pages/publish/publish'})},
  goBack(){const pages=getCurrentPages();if(pages.length>1){wx.navigateBack()}else{wx.reLaunch({url:'/pages/index/index'})}},
  async onLike(e){
    const id=e.currentTarget.dataset.id;if(this._liking)return;this._liking=true
    try{await api.likePost(id);this.setData({posts:this.data.posts.map(p=>String(p.id)===String(id)?{...p,liked:!p.liked,likeCount:Math.max(0,(p.likeCount||0)+(p.liked?-1:1))}:p)})}
    catch(error){popup.toast(this,error.message||'点赞失败',{tone:'error'})}finally{this._liking=false}
  },
  async onFollow(e){
    const p=this.data.posts[Number(e.currentTarget.dataset.index)];if(!p||this._following)return;this._following=true
    try{await api.followUser(p.authorId||p.id);this.setData({posts:this.data.posts.map(row=>String(row.authorId)===String(p.authorId)?{...row,followed:!p.followed}:row)})}
    catch(error){popup.toast(this,error.message||'关注失败',{tone:'error'})}finally{this._following=false}
  },
  // 狐友节奏：点正文/图 → 整页详情；点头像/昵称 → 整页个人主页
  openDetail(e){
    const id=e.currentTarget.dataset.id
    this._needReload=true
    wx.navigateTo({url:`/pages/post-detail/post-detail?id=${id}`})
  },
  openAuthor(e){
    const id=e.currentTarget.dataset.authorId||0
    this._needReload=true
    wx.navigateTo({url:`/pages/user-home/user-home?id=${id}`})
  },
  onShareAppMessage(res){
    const id=res&&res.target&&res.target.dataset?res.target.dataset.id:null
    const p=this.data.posts.find(x=>x.id==id)||this.data.posts[0]
    return { title:p?(p.title||p.content||'校园论坛帖子').slice(0,30):'校园论坛', path:p?`/pages/post-detail/post-detail?id=${p.id}`:'/pages/community/community' }
  }
})
