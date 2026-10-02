package async.m11.p1;

import java.util.List;
import java.util.Set;

/**
 * 여행 통합 검색 결과. (주어진 타입: 수정하지 않는다)
 *
 * @param flights     airline-a 와 airline-b 응답을 합쳐 문자열 오름차순으로 정렬한 목록
 * @param hotels      hotel 응답 1개 또는 빈 목록
 * @param cars        car 응답 1개 또는 빈 목록
 * @param unavailable 실패했거나 시간 안에 응답하지 않은 공급사 이름 ("airline-a", "airline-b", "hotel", "car")
 */
public record SearchResult(List<String> flights, List<String> hotels, List<String> cars, Set<String> unavailable) {

    public SearchResult {
        flights = List.copyOf(flights);
        hotels = List.copyOf(hotels);
        cars = List.copyOf(cars);
        unavailable = Set.copyOf(unavailable);
    }
}
