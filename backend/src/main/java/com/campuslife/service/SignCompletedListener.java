package com.campuslife.service;

import java.time.LocalDate;

/**
 * 签到成功后的附加动作扩展点。
 *
 * 为什么用接口而不是在 SignService 里直接调宿舍：
 *   * 宿舍域依赖签到，签到不该依赖宿舍，否则两个域互相绑死；
 *   * 宿舍判定失败不能回滚签到（设计稿明确要求），所以放在事务提交后执行。
 * V3.1 只有宿舍一个实现。
 */
public interface SignCompletedListener {

    /** 签到已提交成功后调用；实现方自己捕获异常，不得影响签到结果。 */
    void onSigned(Long userId, LocalDate bizDate);
}