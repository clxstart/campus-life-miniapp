# 项目注意事项

- 主工作目录：`E:\goodworlk\campus-life-miniapp`
- 小程序是原生 WXML/WXSS/JS/JSON，当前页面注册在 `app.json`。
- 后端是 Spring Boot + MyBatis-Plus + MySQL；接口统一返回 `{code,msg,data}`。
- 小程序读取接口可以本地兜底，写接口不能静默伪造成功。
- 修改小程序自定义导航栏时注意 `box-sizing: content-box`。
- 运行测试后检查数据库，避免把 E2E 测试数据留在真实数据中。
- 机密配置、上传文件、构建产物不要提交到 Git。
- 本次问题：宠物页 Lottie canvas 需要等 loading 结束、canvas 进入 DOM 后再初始化，并通过自动化验证动画实例是否存在。
