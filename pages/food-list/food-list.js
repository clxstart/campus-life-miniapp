const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const { money } = require('../../utils/food')
const popup = require('../../utils/ui-popup-helper')
Page({
  data:{...getNavigationMetrics(),categories:['全部','奶茶饮品','快餐便当','水果轻食','零食'],category:'全部',keyword:'',schoolScope:'local',shops:[],loading:true,uiPopup:null},
  onLoad(){this._alive=true;this.loadShops()},
  onUnload(){this._alive=false},
  onPullDownRefresh(){this.loadShops().finally(()=>wx.stopPullDownRefresh())},
  async loadShops(){const seq=this._seq=(this._seq||0)+1;this.setData({loading:true});try{const rows=await api.getFoodShops(this.data.category==='全部'?'':this.data.category,this.data.schoolScope,school.getSelectedId());if(!this._alive||seq!==this._seq)return;this._shops=rows.map(s=>({...s,ratingText:Number(s.rating).toFixed(1),minPriceText:money(s.minPrice),deliveryFeeText:money(s.deliveryFee)}));this.filterShops()}catch(error){if(this._alive&&seq===this._seq)popup.toast(this,error.message||'加载失败',{tone:'error'})}finally{if(this._alive&&seq===this._seq)this.setData({loading:false})}},
  chooseCategory(e){this.setData({category:e.currentTarget.dataset.category});this.loadShops()},
  // 本校/全部切换：外卖按校隔离（各校商家不同）
  switchSchoolScope(e){const schoolScope=e.currentTarget.dataset.scope;if(!schoolScope||schoolScope===this.data.schoolScope)return;this.setData({schoolScope,shops:[]});this.loadShops()},
  search(e){this.setData({keyword:e.detail.value});this.filterShops()},
  clearSearch(){this.setData({keyword:''});this.filterShops()},
  filterShops(){const keyword=this.data.keyword.trim().toLowerCase();this.setData({shops:(this._shops||[]).filter(s=>s.name.toLowerCase().includes(keyword))})},
  openShop(e){wx.navigateTo({url:'/pages/food-shop/food-shop?id='+e.currentTarget.dataset.id})},

  goBack(){const pages=getCurrentPages();if(pages.length>1){wx.navigateBack()}else{wx.reLaunch({url:'/pages/index/index'})}},
  // helper 接收带 detail 的事件；输入同步到页面，避免确认时取到旧值。
  onPopupConfirm(e){this._popupConfirm&&this._popupConfirm({detail:e.detail})},
  onPopupCancel(){this._popupCancel&&this._popupCancel()},
  onPopupAction(e){this._popupAction&&this._popupAction({detail:e.detail})},
  onPopupInput(e){this.setData({'uiPopup.inputValue':e.detail.value});this._popupInput&&this._popupInput({detail:e.detail})},
  onPopupClose(){popup.hide(this)}

})
