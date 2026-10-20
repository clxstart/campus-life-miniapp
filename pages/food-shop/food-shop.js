const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { money, bill, orderItems } = require('../../utils/food')
const popup = require('../../utils/ui-popup-helper')
Page({
  data:{...getNavigationMetrics(),shop:null,menu:[],activeCategory:0,visibleGoods:[],counts:{},cartCount:0,subtotal:'0.00',missing:'0.00',loading:true,error:'',uiPopup:null},
  async onLoad(options){this._alive=true;const id=Number(options.id);try{if(!Number.isSafeInteger(id)||id<1)throw new Error('店铺不存在');const [shops,menu]=await Promise.all([api.getFoodShops(),api.getFoodMenu(id)]);if(!this._alive)return;const shop=shops.find(s=>Number(s.id)===id);if(!shop)throw new Error('店铺不存在');const decorated=menu.map(group=>({...group,goods:group.goods.map(g=>({...g,priceText:money(g.price)}))}));this.setData({shop:{...shop,minPriceText:money(shop.minPrice),deliveryFeeText:money(shop.deliveryFee)},menu:decorated,visibleGoods:decorated[0]?decorated[0].goods:[],loading:false});this.updateCart()}catch(error){if(this._alive)this.setData({loading:false,error:error.message||'加载失败'})}},
  onUnload(){this._alive=false},
  chooseCategory(e){const index=Number(e.currentTarget.dataset.index);const group=this.data.menu[index];if(group)this.setData({activeCategory:index,visibleGoods:group.goods})},
  changeCount(e){const id=Number(e.currentTarget.dataset.id);const delta=Number(e.currentTarget.dataset.delta);if(!this.data.menu.some(group=>group.goods.some(g=>Number(g.id)===id)))return;const count=(this.data.counts[id]||0)+delta;if(count>99){popup.toast(this,'单件商品最多购买99份');return}const counts={...this.data.counts};if(count<=0)delete counts[id];else counts[id]=count;this.setData({counts});this.updateCart()},
  cartItems(){const wanted=Object.keys(this.data.counts).map(id=>({goodsId:Number(id),count:this.data.counts[id]}));return wanted.length?orderItems(this.data.menu,wanted):[]},
  updateCart(){const items=this.cartItems();const fees=bill(this.data.shop,items);this.setData({cartCount:items.reduce((n,item)=>n+item.count,0),subtotal:fees.subtotal,missing:fees.missing})},
  checkout(){try{const items=this.cartItems();if(!items.length){popup.toast(this,'先选一份喜欢的吧');return}if(Number(this.data.missing)>0){popup.toast(this,'还差¥'+this.data.missing+'起送');return}wx.setStorageSync('pending_order',{shopId:this.data.shop.id,shopName:this.data.shop.name,items:items.map(({goodsId,name,price,count})=>({goodsId,name,price,count}))});wx.navigateTo({url:'/pages/food-order/food-order'})}catch(error){popup.toast(this,error.message||'无法保存订单，请重试',{tone:'error'})}},

  goBack(){const pages=getCurrentPages();if(pages.length>1){wx.navigateBack()}else{wx.reLaunch({url:'/pages/index/index'})}},
  // helper 接收带 detail 的事件；输入同步到页面，避免确认时取到旧值。
  onPopupConfirm(e){this._popupConfirm&&this._popupConfirm({detail:e.detail})},
  onPopupCancel(){this._popupCancel&&this._popupCancel()},
  onPopupAction(e){this._popupAction&&this._popupAction({detail:e.detail})},
  onPopupInput(e){this.setData({'uiPopup.inputValue':e.detail.value});this._popupInput&&this._popupInput({detail:e.detail})},
  onPopupClose(){popup.hide(this)}

})
