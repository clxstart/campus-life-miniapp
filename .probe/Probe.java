import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.allow.WordAllows;
import java.util.*;

public class Probe {
  public static void main(String[] a) {
    List<String> deny = List.of("刷单","押金","保证金","先交钱","垫付","返利","日赚","月入过万","代写","代考","代课","包过","内部渠道","包录取","兼职日结高薪","无需经验高薪","加微信私聊","加QQ","刷信誉","点赞返现","任务佣金","垫付返现");
    List<String> allow = List.of("靠窗","靠背","靠近","依靠","可靠","靠山","靠着","靠谱","兼职","信息","上课","下课","自习","作业","考试","复习","宿舍","食堂","图书馆","老师","同学","课程","选修","成绩","押金退还","退款","退押金","预约","代取","代拿","代购","方便","便利","包邮","包月","包年");
    SensitiveWordBs bs = SensitiveWordBs.newInstance()
      .wordAllow(WordAllows.chains(WordAllows.system(), () -> allow))
      .wordDeny(() -> deny)
      .ignoreCase(true).ignoreWidth(true).ignoreNumStyle(true).init();
    for (String s : List.of("台灯12345","台灯123456789","台灯1234567890","092725904","abc092725904","正常二手台灯","加QQ","E2E校归属测试")) {
      System.out.println(s + " -> " + bs.findAll(s));
    }
    System.out.println("--- allow 生效检查 ---");
    System.out.println("押金 -> " + bs.findAll("押金"));
    System.out.println("押金退还 -> " + bs.findAll("押金退还"));
  }
}
