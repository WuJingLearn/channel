package org.javaboy.codeexample.concurrency;

import org.openjdk.jol.info.ClassLayout;

/**
 * synchronized 锁升级全过程演示
 *
 * 通过 JOL（Java Object Layout）工具观察对象头 Mark Word 的变化，
 * 直观展示：无锁 → 偏向锁 → 轻量级锁 → 重量级锁 的升级过程。
 *
 * 运行前需添加 JOL 依赖：
 *   Maven:
 *     <dependency>
 *       <groupId>org.openjdk.jol</groupId>
 *       <artifactId>jol-core</artifactId>
 *       <version>0.17</version>
 *     </dependency>
 *
 * 运行参数（JDK 15 以下需开启偏向锁）：
 *   java -XX:+UseBiasedLocking -XX:BiasedLockingStartupDelay=0 SynchronizedLockEscalationDemo
 *
 * JDK 15+ 偏向锁已默认关闭，升级路径变为：无锁 → 轻量级锁 → 重量级锁
 */
public class SynchronizedLockEscalationDemo {

    public static void main(String[] args) throws InterruptedException {
        demonstrateBiasedLock();
        demonstrateLightweightLock();
        demonstrateHeavyweightLock();
    }

    // ======================== 阶段一：偏向锁 ========================

    /**
     * 演示：无锁 → 偏向锁
     * 场景：只有一个线程反复进入同步块，锁始终偏向该线程
     */
    private static void demonstrateBiasedLock() {
        Object biasedLock = new Object();

        System.out.println("========== 阶段一：偏向锁 ==========");
        System.out.println("【无锁状态】对象刚创建，Mark Word 存储 hashCode + GC 年龄");
        System.out.println(ClassLayout.parseInstance(biasedLock).toPrintable());

        // 同一个线程（main）反复进入同步块
        synchronized (biasedLock) {
            // 第一次进入：CAS 将当前线程 ID 写入 Mark Word，升级为偏向锁
            // Mark Word: [线程ID | epoch | GC年龄 | 偏向标志=1 | 锁标志=01]
            System.out.println("【偏向锁状态】main 线程第一次进入同步块");
            System.out.println(ClassLayout.parseInstance(biasedLock).toPrintable());
        }

        synchronized (biasedLock) {
            // 第二次进入：只需比较 Mark Word 中的线程 ID == 当前线程 ID
            // 匹配成功，直接进入，无需 CAS → 几乎零开销
            System.out.println("【偏向锁状态】main 线程第二次进入，无需 CAS，直接复入");
            System.out.println(ClassLayout.parseInstance(biasedLock).toPrintable());
        }

        System.out.println();
    }

    // ======================== 阶段二：轻量级锁 ========================

    /**
     * 演示：偏向锁 → 轻量级锁
     * 场景：两个线程交替进入同步块（无同时竞争），触发偏向锁撤销
     */
    private static void demonstrateLightweightLock() throws InterruptedException {
        Object lightweightLock = new Object();

        System.out.println("========== 阶段二：轻量级锁 ==========");

        // 线程 A 先获取偏向锁
        synchronized (lightweightLock) {
            System.out.println("【偏向锁】main 线程持有锁");
            System.out.println(ClassLayout.parseInstance(lightweightLock).toPrintable());
        }

        // 线程 B 交替进入 → 触发偏向锁撤销 → 升级为轻量级锁
        Thread alternateThread = new Thread(() -> {
            synchronized (lightweightLock) {
                // 发现 Mark Word 中的线程 ID 不是自己 → 偏向锁撤销
                // 在当前线程栈帧中创建 Lock Record
                // CAS 将 Mark Word 替换为指向 Lock Record 的指针
                // Mark Word: [Lock Record 指针 | 锁标志=00]
                System.out.println("【轻量级锁】alternate 线程进入，偏向锁撤销，升级为轻量级锁");
                System.out.println(ClassLayout.parseInstance(lightweightLock).toPrintable());
            }
        }, "alternate-thread");

        alternateThread.start();
        alternateThread.join();

        System.out.println();
    }

    // ======================== 阶段三：重量级锁 ========================

    /**
     * 演示：轻量级锁 → 重量级锁
     * 场景：多个线程同时竞争同一把锁，CAS 自旋失败，锁膨胀
     */
    private static void demonstrateHeavyweightLock() throws InterruptedException {
        Object heavyweightLock = new Object();

        System.out.println("========== 阶段三：重量级锁 ==========");

        // 线程 A 持有锁并执行耗时操作
        Thread holderThread = new Thread(() -> {
            synchronized (heavyweightLock) {
                System.out.println("【持有锁】holder 线程获取锁，开始耗时操作...");
                System.out.println(ClassLayout.parseInstance(heavyweightLock).toPrintable());
                try {
                    // 模拟耗时操作，让其他线程有时间来竞争
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("【释放锁】holder 线程释放锁");
                System.out.println(ClassLayout.parseInstance(heavyweightLock).toPrintable());
            }
        }, "holder-thread");

        holderThread.start();
        // 等待 holder 线程先拿到锁
        Thread.sleep(200);

        // 线程 B、C 同时竞争 → CAS 自旋失败 → 锁膨胀为重量级锁
        Thread competitorOne = new Thread(() -> {
            synchronized (heavyweightLock) {
                // holder 还没释放锁，CAS 自旋获取轻量级锁失败
                // 自旋次数超过阈值 → 锁膨胀为重量级锁
                // Mark Word: [ObjectMonitor 指针 | 锁标志=10]
                // 当前线程进入 ObjectMonitor._EntryList 阻塞
                // 底层：park() → pthread_cond_wait() → futex(FUTEX_WAIT)
                System.out.println("【重量级锁】competitor-1 获取到锁（从 EntryList 中被唤醒）");
                System.out.println(ClassLayout.parseInstance(heavyweightLock).toPrintable());
            }
        }, "competitor-1");

        Thread competitorTwo = new Thread(() -> {
            synchronized (heavyweightLock) {
                System.out.println("【重量级锁】competitor-2 获取到锁（从 EntryList 中被唤醒）");
                System.out.println(ClassLayout.parseInstance(heavyweightLock).toPrintable());
            }
        }, "competitor-2");

        competitorOne.start();
        competitorTwo.start();

        holderThread.join();
        competitorOne.join();
        competitorTwo.join();

        System.out.println();
        System.out.println("========== 锁升级演示完成 ==========");
        System.out.println("升级路径：无锁(01) → 偏向锁(01,偏向=1) → 轻量级锁(00) → 重量级锁(10)");
        System.out.println("注意：锁只能升级，不能降级");
    }
}
