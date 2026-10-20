/** Natural Organic 风格 token —— 严格对齐 StyleKit 规范
 *  禁止：冷色（blue/purple/cyan）、纯黑、rounded-none/sm、shadow-lg 以上、渐变
 */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  theme: {
    extend: {
      colors: {
        // 大地色系：全部来自 stylekit natural-organic 调色板
        organic: {
          primary: '#5c4033',  // 石棕（主色）
          cream: '#faf6f1',    // 暖米色（背景）
          sage: '#8b9d77',     // 鼠尾草绿（强调）
          tan: '#d4a373',      // 暖棕黄（次强调）
          clay: '#e9e0d4',     // 浅陶土
          moss: '#75a191'      // 灰青绿（辅助）
        }
      },
      fontFamily: {
        // 标题用衬线（禁 Inter/Roboto 这类过度使用的无衬线）
        serif: ['"Noto Serif SC"', '"Songti SC"', 'Georgia', 'serif'],
        sans: ['"Noto Sans SC"', '"PingFang SC"', 'system-ui', 'sans-serif']
      },
      borderRadius: {
        organic: '2rem',
        blob: '42% 58% 63% 37% / 41% 44% 56% 59%'
      },
      maxWidth: {
        content: '72rem'
      },
      transitionDuration: {
        organic: '500ms'
      }
    }
  },
  plugins: []
}
