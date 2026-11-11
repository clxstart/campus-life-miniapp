# -*- coding: utf-8 -*-
"""批量把 wxml/js 里写死的本地图片路径改成远程资源解析（utils/remote-assets.js）。

为什么用脚本而不是手改：同一张图在多处出现（列表页 + 详情页兜底），
手工改容易漏一处，漏了那处上线就是白图。
"""
import io
import os

os.chdir(r"E:\goodworlk\campus-life-miniapp")

EDITS = [
    ("pages/campus-offer/campus-offer.js",
     "const { getNavigationMetrics } = require('../../utils/navigation')\nPage({\n  data: { ...getNavigationMetrics() },",
     "const { getNavigationMetrics } = require('../../utils/navigation')\nconst { assetMap } = require('../../utils/remote-assets')\nPage({\n  // 广告位插画：wxml 调不了函数，先在 data 里解析（utils/remote-assets.js）\n  data: { ...getNavigationMetrics(), art: assetMap({ laptop: '/assets/home/laptop-silver.webp' }) },"),
    ("pages/campus-offer/campus-offer.wxml",
     'src="/assets/home/laptop-silver.webp"',
     'src="{{art.laptop}}"'),
    ("pages/food-list/food-list.js",
     "const popup = require('../../utils/ui-popup-helper')\nPage({\n  data:{...getNavigationMetrics(),",
     "const popup = require('../../utils/ui-popup-helper')\nconst { asset } = require('../../utils/remote-assets')\nPage({\n  data:{...getNavigationMetrics(),heroArt:asset('/assets/home/food-fresh.webp'),"),
    ("pages/food-list/food-list.wxml",
     'src="/assets/home/food-fresh.webp"',
     'src="{{heroArt}}"'),
    ("pages/market-detail/market-detail.js",
     "const popup = require('../../utils/ui-popup-helper')\n\nPage({\n  data: {\n    ...getNavigationMetrics(),",
     "const popup = require('../../utils/ui-popup-helper')\nconst { asset } = require('../../utils/remote-assets')\n\nPage({\n  data: {\n    ...getNavigationMetrics(),\n    // 无图时的兜底插画（和列表页同一张）\n    defaultCover: asset('/assets/home/secondhand-box.webp'),"),
    ("pages/market-detail/market-detail.wxml",
     "{{item.image || '/assets/home/secondhand-box.webp'}}",
     "{{item.image || defaultCover}}"),
    ("pages/market-list/market-list.js",
     "const popup = require('../../utils/ui-popup-helper')\n",
     "const popup = require('../../utils/ui-popup-helper')\nconst { asset } = require('../../utils/remote-assets')\n"),
    ("pages/market-list/market-list.js",
     "    mode: 1,",
     "    mode: 1,\n    // 无图时的兜底插画\n    defaultCover: asset('/assets/home/secondhand-box.webp'),"),
    ("pages/market-list/market-list.wxml",
     "{{item.image || '/assets/home/secondhand-box.webp'}}",
     "{{item.image || defaultCover}}"),
    ("pages/learn/learn.js",
     "const { categories, findCategory } = require('../../utils/learn')",
     "const { categories, findCategory } = require('../../utils/learn')\nconst { assetMap } = require('../../utils/remote-assets')"),
    ("pages/learn/learn.js",
     "    ...getNavigationMetrics(), categories, category: 'daike', current: findCategory('daike'),",
     "    ...getNavigationMetrics(), categories, category: 'daike', current: findCategory('daike'),\n    // 头图与卡片插画（wxml 调不了函数，先解析好）\n    art: assetMap({ hero: '/assets/learn/hero.jpg', desk: '/assets/learn/desk.jpg' }),"),
    ("pages/learn/learn.wxml",
     'src="/assets/learn/hero.jpg"',
     'src="{{art.hero}}"'),
    ("pages/learn/learn.wxml",
     'src="/assets/learn/desk.jpg"',
     'src="{{art.desk}}"'),
    ("pages/discover/discover.js",
     "cover: circle.cover || '/assets/social/spring-picnic.webp',",
     "cover: circle.cover || asset('/assets/social/spring-picnic.webp'),"),
    ("pages/profile/profile.js",
     "cover: item.cover || '/assets/home/secondhand-box.webp',",
     "cover: item.cover || asset('/assets/home/secondhand-box.webp'),"),
]

ok = miss = 0
for path, old, new in EDITS:
    s = io.open(path, encoding="utf-8").read()
    if old not in s:
        print("MISS", path, "|", old[:60].replace("\n", "\\n"))
        miss += 1
        continue
    s = s.replace(old, new, 1)
    io.open(path, "w", encoding="utf-8", newline="").write(s)
    print("OK  ", path)
    ok += 1
print("done ok=%d miss=%d" % (ok, miss))