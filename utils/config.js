// 后端地址集中在这里。
//
// 小程序没有 process.env，环境切换只能靠这个常量：发布前把 ENV 改成 'prod'。
// 改完之后请同时确认微信公众平台「request 合法域名」里加了对应的 https 域名。
const ENV = 'dev'

const ENDPOINTS = {
  dev: {
    // 本机后端（spring-boot:run 默认 8080）
    api: 'http://127.0.0.1:8080/api',
    // 上传图片回显的基址，通常和 api 同源
    asset: 'http://127.0.0.1:8080'
  },
  prod: {
    // 部署后替换成真实域名（必须 https，且要在小程序后台配置合法域名）
    api: 'https://api.your-domain.example/api',
    asset: 'https://api.your-domain.example'
  }
}

const current = ENDPOINTS[ENV] || ENDPOINTS.dev

module.exports = {
  ENV,
  BASE_URL: current.api,
  ASSET_BASE_URL: current.asset,
  ENDPOINTS
}
