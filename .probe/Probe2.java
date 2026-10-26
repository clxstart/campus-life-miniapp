import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.allow.WordAllows;
import java.util.*;

public class Probe2 {
  static List<String> deny = List.of("刷单","押金","保证金","先交钱","垫付","返利","日赚","月入过万","代写","代考","代课","包过","内部渠道","包录取","兼职日结高薪","无需经验高薪","加微信私聊","加QQ","刷信誉","点赞返现","任务佣金","垫付返现");
  static List<String> allow = List.of("靠窗","靠背","靠近","依靠","可靠","靠山","靠着","靠谱","兼职","信息","上课","下课","自习","作业","考试","复习","宿舍","食堂","图书馆","老师","同学","课程","选修","成绩","押金退还","退款","退押金","预约","代取","代拿","代购","方便","便利","包邮","包月","包年");
  static void run(String label, SensitiveWordBs bs, String[] inputs) {
    System.out.println("== " + label);
    for (String s : inputs) System.out.println("  " + s + " -> " + bs.findAll(s));
  }
  public static void main(String[] a) {
    String[] inputs = {"傻逼","法轮功","靠窗","押金退还","刷单","兼职日结高薪","台灯12345678","台灯123456789","手机13800138000","QQ123456"};
    SensitiveWordBs withSystemAllow = SensitiveWordBs.newInstance()
      .wordAllow(WordAllows.chains(WordAllows.system(), () -> allow))
      .wordDeny(() -> deny).ignoreCase(true).ignoreWidth(true).ignoreNumStyle(true).init();
    run("现状配置（system allow + 自定义 deny）", withSystemAllow, inputs);

    SensitiveWordBs noIgnoreNum = SensitiveWordBs.newInstance()
      .wordAllow(WordAllows.chains(WordAllows.system(), () -> allow))
      .wordDeny(() -> deny).ignoreCase(true).ignoreWidth(true).ignoreNumStyle(false).init();
    run("关掉 ignoreNumStyle", noIgnoreNum, inputs);

    SensitiveWordBs plain = SensitiveWordBs.newInstance()
      .wordDeny(() -> deny).init();
    run("极简：只给 deny，不加 allow/ignore", plain, inputs);
  }
}
