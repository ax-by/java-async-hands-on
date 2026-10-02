package async.m08.domain;

/** 카드사가 승인을 거절했다(한도 초과, 정지 카드 등). 장애가 아니라 "정상적인 거절"이다. */
public class CardDeclinedException extends RuntimeException {

    public CardDeclinedException(String message) {
        super(message);
    }
}
