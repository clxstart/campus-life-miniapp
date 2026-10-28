const popup = require('../../utils/ui-popup-helper')
const api = require('../../utils/api')
const { formatTime } = require('../../utils/format-time')
const { getNavigationMetrics } = require('../../utils/navigation')
Page({
  data:{...getNavigationMetrics(),post:null,comments:[],loading:true,commentsLoading:false,commentDraft:'',offline:false,uiPopup:null,commentSending:false},
  onPopupConfirm(e){this._popupConfirm&&this._popupConfirm(e)},
  onPopupCancel(){this._popupCancel&&this._popupCancel()},
  onPopupAction(e){this._popupAction&&this._popupAction(e)},
  onPopupInput(e){this._popupInput&&this._popupInput(e)},
  onPopupClose(){popup.hide(this)},
  onLoad(q){
    this.postId = q.id
    this.fetch()
  },
  fetch(){
    this.setData({loading:true})
    api.getPost(this.postId).then(p=>{
      const avatarText=(p.authorNickname||'同')[0]
      // 详情页展示全文，不做3行截断
      this.setData({post:{...p, expanded:true, avatarText, createdAt:formatTime(p.createdAt)}, offline:false,uiPopup:null,commentSending:false})
      api.viewPost(this.postId).catch(error=>console.warn('[浏览上报]',error.message))
    }).catch(()=>this.setData({loading:false,offline:true})).finally(()=>this.fetchComments())
  },
  // 评论列表统一入口：按 id 去重后再 setData。
  // 本地「刚发的评论」和接口返回的同一条评论 id 相同，
  // 如果两者先后都被写进数组，就会出现两条一模一样的评论。
  setComments(list){
    const seen=new Set(), dedup=[]
    for(const item of (list||[])){
      const key=String(item&&item.id)
      if(seen.has(key))continue
      seen.add(key); dedup.push(item)
    }
    this.setData({comments:dedup})
  },
  fetchComments(silent=false){
    if(!silent)this.setData({commentsLoading:true})
    api.getPostComments(this.postId)
      .then(res=>{
        const list=(res&&res.list)?res.list:(res||[])
        const normalized=list.map(x=>({
          ...x,
          avatarText:(x.authorNickname||'同')[0],
          createdAt:formatTime(x.createdAt),
          likeCount:x.likeCount||0,
          liked:!!x.liked
        }))
        this.setComments(normalized)
      })
      .catch(()=>{ if(!silent) this.setData({comments:[]}) })
      .finally(()=>{ if(!silent) this.setData({commentsLoading:false}) })
  },
  openAuthor(){ const p=this.data.post; if(!p)return; wx.navigateTo({url:`/pages/user-home/user-home?id=${p.authorId||0}`}) },
  goBack(){const pages=getCurrentPages();if(pages.length>1){wx.navigateBack()}else{wx.reLaunch({url:'/pages/index/index'})}},
  async onFollow(){
    const p=this.data.post;if(!p||this._following)return;this._following=true
    try{await api.followUser(p.authorId||p.id);this.setData({'post.followed':!p.followed})}
    catch(error){popup.toast(this,error.message||'关注失败',{tone:'error'})}finally{this._following=false}
  },
  noop(){},
  async onLike(){
    const p=this.data.post;if(!p||this._liking)return;this._liking=true
    try{await api.likePost(p.id);this.setData({'post.liked':!p.liked,'post.likeCount':Math.max(0,(p.likeCount||0)+(p.liked?-1:1))})}
    catch(error){popup.toast(this,error.message||'点赞失败',{tone:'error'})}finally{this._liking=false}
  },
  // 收藏：先本地生效再上报，失败回滚
  async onFavorite(){
    const p=this.data.post;if(!p)return
    const favored=!p.favored
    this.setData({[`post.favored`]:favored})
    try{
      await api.toggleFavorite(p.id)
      popup.toast(this,favored?'已加入收藏':'已取消收藏')
    }catch(e){
      this.setData({[`post.favored`]:!favored})
      popup.toast(this,e.message||'操作失败',{tone:'error'})
    }
  },
  onDraftInput(e){this.setData({commentDraft:e.detail.value})},
  /** 评论点赞 / 取消：先本地生效，失败回滚 */
  async onLikeComment(e){
    const id=e.currentTarget.dataset.id
    if(!id)return
    const idx=this.data.comments.findIndex(c=>String(c.id)===String(id))
    if(idx<0)return
    const item=this.data.comments[idx]
    const liked=!item.liked
    const nextCount=Math.max(0,(item.likeCount||0)+(liked?1:-1))
    this.setData({
      [`comments[${idx}].liked`]:liked,
      [`comments[${idx}].likeCount`]:nextCount
    })
    try{
      const res=await api.likeComment(id,liked)
      if(res&&typeof res.likeCount==='number'){
        this.setData({[`comments[${idx}].likeCount`]:res.likeCount})
      }
    }catch(error){
      // 回滚
      this.setData({
        [`comments[${idx}].liked`]:!liked,
        [`comments[${idx}].likeCount`]:item.likeCount||0
      })
      popup.toast(this,error.message||'操作失败',{tone:'error'})
    }
  },
  /** 回复某条评论：把输入框填成「@用户名 」并聚焦 */
  replyComment(e){
    const name=e.currentTarget.dataset.name||'同学'
    this.setData({commentDraft:`@${name} `})
  },
  async submitComment(){
    const {post,commentDraft}=this.data
    if(!post||!commentDraft.trim()||this.data.commentSending)return
    const content=commentDraft.trim()
    this.setData({commentSending:true})
    try{
      const result=await api.addComment(post.id,content)
      // 先本地插入，保证「发完立刻能看到」
      const c={id:result.id,content,authorNickname:'我',avatarText:'我',createdAt:'刚刚',likeCount:0,liked:false}
      this.setComments(this.data.comments.concat(c))
      this.setData({commentDraft:'','post.commentCount':(post.commentCount||0)+1})
      // 再静默拉一次服务端列表做对账：
      // 服务端是唯一数据源，避免「本地追加」与「接口返回」两条并存导致评论重复
      this.fetchComments(true)
    }
    catch(error){popup.toast(this,error.message||'评论失败',{tone:'error'})}
    finally{this.setData({commentSending:false})}
  },
  chatWithAuthor(){
    const p=this.data.post;if(!p)return
    wx.navigateTo({url:`/pages/chat/chat?id=${p.authorId||0}&name=${encodeURIComponent(p.authorNickname||'同学')}&avatar=${encodeURIComponent(p.authorNickname?p.authorNickname[0]:'同')}&color=${encodeURIComponent('#7EB6F6')}`})
  },
  previewImage(){ const p=this.data.post; if(p&&p.image) wx.previewImage({urls:[p.image]}) },
  // 「更多」菜单：举报（事后处理）+ 屏蔽（事前自保），两个动作放一起
  reportPost(){
    const p = this.data.post
    if (!p || !p.id) return
    popup.sheet(this, {
      title: '这条帖子',
      actions: [{ label: '举报这条帖子' }, { label: '屏蔽作者' }],
      action: ({ item }) => {
        const label = item && item.label
        if (!label) return
        if (label === '屏蔽作者') this.blockAuthor()
        else this.chooseReportReason()
      }
    })
  },
  // 举报：选原因后提交到管理端队列，由人判断，不自动下架
  chooseReportReason(){
    const p = this.data.post
    if (!p || !p.id) return
    const reasons = ['广告', '辱骂', '色情', '诈骗', '侵权', '其他']
    popup.sheet(this, {
      title: '举报原因',
      actions: reasons.map(r => ({ label: r })),
      action: async ({ item }) => {
        const reason = item && item.label
        if (!reason) return
        try {
          await api.reportContent('post', p.id, reason, '')
          popup.toast(this, '已收到举报，我们会尽快处理', { tone: 'success' })
        } catch (error) {
          popup.toast(this, error.message || '举报失败，请稍后再试', { tone: 'error' })
        }
      }
    })
  },
  // 屏蔽作者：双向不可见（你看不到 TA 的帖子，TA 也看不到你的，也无法互发私信）
  blockAuthor(){
    const p = this.data.post
    const authorId = p && p.authorId
    if (!authorId) { popup.toast(this, '无法获取作者信息', { tone: 'warn' }); return }
    if (Number(authorId) === Number(api.currentUserId())) { popup.toast(this, '不能屏蔽自己', { tone: 'warn' }); return }
    popup.modal(this, {
      title: '屏蔽作者',
      message: '屏蔽后你们将互相看不到对方的帖子和评论，也无法互发私信。可在「我的 → 设置 → 黑名单」里解除。',
      showCancel: true,
      confirmText: '屏蔽',
      confirm: async () => {
        try {
          const result = await api.blockUser(authorId)
          if (result && result.blocked) {
            popup.toast(this, '已屏蔽，将不再看到 TA 的内容', { tone: 'success' })
            setTimeout(() => this.goBack(), 800)
          } else {
            popup.toast(this, '已解除屏蔽', { tone: 'success' })
          }
        } catch (error) {
          popup.toast(this, error.message || '操作失败，请稍后再试', { tone: 'error' })
        }
      }
    })
  },
  onShareAppMessage(){ const p=this.data.post; return { title:p?(p.title||'校园论坛帖子'):'校园论坛', path:`/pages/post-detail/post-detail?id=${this.postId}` } }
})

