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
  fetchComments(){
    this.setData({commentsLoading:true})
    api.getPostComments(this.postId)
      .then(res=>{
        const list=(res&&res.list)?res.list:(res||[])
        const normalized=list.map(x=>({...x,avatarText:(x.authorNickname||'同')[0],createdAt:formatTime(x.createdAt)}))
        this.setData({comments:normalized})
      })
      .catch(()=>this.setData({comments:[]}))
      .finally(()=>this.setData({commentsLoading:false}))
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
  async submitComment(){
    const {post,commentDraft}=this.data;if(!post||!commentDraft.trim()||this.data.commentSending)return
    const content=commentDraft.trim();this.setData({commentSending:true})
    try{const result=await api.addComment(post.id,content);const c={id:result.id,content,authorNickname:'我',avatarText:'我',createdAt:'刚刚'};this.setData({comments:this.data.comments.concat(c),commentDraft:'','post.commentCount':(post.commentCount||0)+1})}
    catch(error){popup.toast(this,error.message||'评论失败',{tone:'error'})}finally{this.setData({commentSending:false})}
  },
  chatWithAuthor(){
    const p=this.data.post;if(!p)return
    wx.navigateTo({url:`/pages/chat/chat?id=${p.authorId||0}&name=${encodeURIComponent(p.authorNickname||'同学')}&avatar=${encodeURIComponent(p.authorNickname?p.authorNickname[0]:'同')}&color=${encodeURIComponent('#7EB6F6')}`})
  },
  previewImage(){ const p=this.data.post; if(p&&p.image) wx.previewImage({urls:[p.image]}) },
  onShareAppMessage(){ const p=this.data.post; return { title:p?(p.title||'校园论坛帖子'):'校园论坛', path:`/pages/post-detail/post-detail?id=${this.postId}` } }
})
