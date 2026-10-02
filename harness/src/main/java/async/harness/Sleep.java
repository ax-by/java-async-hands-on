package async.harness;

/** 체크 예외 없는 sleep. interrupt 되면 interrupt 상태를 복원하고 IllegalStateException 을 던진다. */
public final class Sleep {

    private Sleep() {
    }

    public static void ms(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("sleep 중 interrupt", e);
        }
    }
}
