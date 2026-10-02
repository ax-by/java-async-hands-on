package async.m08;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.m08.bugs.C01OrderHistory;
import async.m08.bugs.C02ProductDetail;
import async.m08.bugs.C03ProductPage;
import async.m08.bugs.C04OrderPlacement;
import async.m08.bugs.C05OrderPage;
import async.m08.bugs.C06Recommendation;
import async.m08.bugs.C07StockBadge;
import async.m08.bugs.C08Thumbnail;
import async.m08.bugs.C09Payment;
import async.m08.bugs.C10CartPricing;
import async.m08.bugs.C11ShippingQuote;
import async.m08.bugs.C12OrderEvents;
import async.m08.bugs.C13Fulfillment;
import async.m08.bugs.C14SalesReport;
import async.m08.bugs.C15ProductSearch;
import async.m08.bugs.C16OrderStatus;
import async.m08.bugs.C17OrderDetail;
import async.m08.bugs.C18Checkout;
import async.m08.bugs.C19ProductCard;
import async.m08.bugs.C20OrderExport;
import async.m08.bugs.C21PriceCache;
import async.m08.bugs.C22PaymentCancel;
import async.m08.probe.C01Probe;
import async.m08.probe.C02Probe;
import async.m08.probe.C03Probe;
import async.m08.probe.C04Probe;
import async.m08.probe.C05Probe;
import async.m08.probe.C06Probe;
import async.m08.probe.C07Probe;
import async.m08.probe.C08Probe;
import async.m08.probe.C09Probe;
import async.m08.probe.C10Probe;
import async.m08.probe.C11Probe;
import async.m08.probe.C12Probe;
import async.m08.probe.C13Probe;
import async.m08.probe.C14Probe;
import async.m08.probe.C15Probe;
import async.m08.probe.C16Probe;
import async.m08.probe.C17Probe;
import async.m08.probe.C18Probe;
import async.m08.probe.C19Probe;
import async.m08.probe.C20Probe;
import async.m08.probe.C21Probe;
import async.m08.probe.C22Probe;
import async.m08.probe.Diagnosis;

/**
 * m08 예측 채점. {@code bugs} 의 원본 코드를 관측 조건대로 실행하고, <b>측정값으로 분류한 증상</b>과 예측을 비교한다.
 * 측정 근거는 표준 출력의 "측정 결과" 블록에 나온다. Predictions 를 모두 채운 뒤 실행한다.
 */
@Timeout(20)
class SymptomTest {

    /**
     * 증상(측정)과 체크리스트 번호(해시 비교)를 채점한다.
     *
     * @param acceptedChecklistHashes 정답으로 인정하는 체크리스트 번호들의 해시 ("cNN#번호" 의 SHA-256)
     */
    private static void grade(String symptomId, String checklistId, Symptom predictedSymptom, String predictedChecklist,
                              Diagnosis measured, String rules, String... acceptedChecklistHashes) {
        System.out.println(measured.report());
        String caseKey = symptomId.split("\\.")[1];
        String checklistAnswer = predictedChecklist == null ? null : caseKey + "#" + predictedChecklist.trim().replace("#", "");
        List<String> accepted = Arrays.asList(acceptedChecklistHashes);
        String expected = checklistAnswer != null && accepted.contains(Grader.sha256(Grader.normalize(checklistAnswer)))
                ? Grader.sha256(Grader.normalize(checklistAnswer))
                : accepted.get(0);
        new Grader()
                .check(symptomId, predictedSymptom, measured.symptom(), rules)
                .checkHashed(checklistId, checklistAnswer, expected, rules + " + docs/review-checklist.md")
                .verify();
    }

    @Test
    void c01() {
        try (C01Probe p = new C01Probe()) {
            Diagnosis d = p.run(new C01OrderHistory(p.orderDb, p.io)::recentOrders);
            grade("m08.c01.symptom", "m08.c01.checklist", Predictions.C01_SYMPTOM, Predictions.C01_CHECKLIST, d, "R7,R5",
                    "f53dd8f5b14fad7d1daf64751aa99af3c13bb4846c82833aa60a3d5e4a0e40b9");
        }
    }

    @Test
    void c02() {
        try (C02Probe p = new C02Probe()) {
            Diagnosis d = p.run(new C02ProductDetail(p.productDb, p.dbPool)::find);
            grade("m08.c02.symptom", "m08.c02.checklist", Predictions.C02_SYMPTOM, Predictions.C02_CHECKLIST, d, "R3,R4",
                    "3982c4480b6face34a0ce069161091f6e7350bb705fa484eda95262d746e6fc7");
        }
    }

    @Test
    void c03() {
        try (C03Probe p = new C03Probe()) {
            Diagnosis d = p.run(new C03ProductPage(p.productDb, p.stockDb, p.reviewDb, p.io)::page);
            grade("m08.c03.symptom", "m08.c03.checklist", Predictions.C03_SYMPTOM, Predictions.C03_CHECKLIST, d, "R5,R7",
                    "e0df26936b0c7a4756eecd2b175b6759ac6e4e27acf540b4e8ff8529a8555bca");
        }
    }

    @Test
    void c04() {
        try (C04Probe p = new C04Probe()) {
            Diagnosis d = p.run(new C04OrderPlacement(p.orderDb, p.loyaltyDb, p.io)::place);
            grade("m08.c04.symptom", "m08.c04.checklist", Predictions.C04_SYMPTOM, Predictions.C04_CHECKLIST, d, "R6",
                    "6b8535a3a2da07b59568b571de607721fb854debf0614de315e3d0c824c4e097");
        }
    }

    @Test
    void c05() {
        try (C05Probe p = new C05Probe()) {
            Diagnosis d = p.run(new C05OrderPage(p.orderClient, p.couponRepository, p.historyRepository, p.dbPool)::orderPage);
            grade("m08.c05.symptom", "m08.c05.checklist", Predictions.C05_SYMPTOM, Predictions.C05_CHECKLIST, d, "R1,R4",
                    "c86ed96fbc340754553c21cf6451a21a34163253f7f68b1973ea24123bd7d9f7");
        }
    }

    @Test
    void c06() {
        try (C06Probe p = new C06Probe()) {
            Diagnosis d = p.run(new C06Recommendation(p.recoClient)::forProduct);
            grade("m08.c06.symptom", "m08.c06.checklist", Predictions.C06_SYMPTOM, Predictions.C06_CHECKLIST, d, "R8,R9",
                    "4b28cf05f3e6966f88aca54afdd55263c1c27bfb8ca88dba1b602905e3f6a329");
        }
    }

    @Test
    void c07() {
        try (C07Probe p = new C07Probe()) {
            Diagnosis d = p.run(new C07StockBadge(p.inventoryClient)::badge);
            grade("m08.c07.symptom", "m08.c07.checklist", Predictions.C07_SYMPTOM, Predictions.C07_CHECKLIST, d, "R1,R4",
                    "f73006624fd5ca257b6865e0b13963a0f9844cee4b0dcd5cc5fc3353daf5b974");
        }
    }

    @Test
    void c08() {
        try (C08Probe p = new C08Probe()) {
            Diagnosis d = p.run(new C08Thumbnail(p.imageCdn, p.imagePool)::thumbnails);
            grade("m08.c08.symptom", "m08.c08.checklist", Predictions.C08_SYMPTOM, Predictions.C08_CHECKLIST, d, "R2,R11",
                    "19e6325998024e5aaea495cbc3a763443a9b3b8f0e2767b38d36deb003286a8c");
        }
    }

    @Test
    void c09() {
        try (C09Probe p = new C09Probe()) {
            Diagnosis d = p.run(new C09Payment(p.paymentGateway)::pay);
            grade("m08.c09.symptom", "m08.c09.checklist", Predictions.C09_SYMPTOM, Predictions.C09_CHECKLIST, d, "R8",
                    "a28a2a79bcc9d8617f7419e9bf1140c5de108a60c13310ae71fedb3fdf83dba6");
        }
    }

    @Test
    void c10() {
        try (C10Probe p = new C10Probe()) {
            Diagnosis d = p.run(new C10CartPricing(p.cartStore, p.pricingClient)::priceCart);
            grade("m08.c10.symptom", "m08.c10.checklist", Predictions.C10_SYMPTOM, Predictions.C10_CHECKLIST, d, "R1,R4,R5",
                    "d2dd71dceadc348bbc487ee9410320424e88c00ffc280e13e619f1c633518eee",
                    "2d0683c923e9f35a11f2e1e56ed4a1626e4b0ba0826b92b9f5dc2fc141cc09cf");
        }
    }

    @Test
    void c11() {
        try (C11Probe p = new C11Probe()) {
            Diagnosis d = p.run(new C11ShippingQuote(p.carrierA, p.carrierB)::cheapest);
            grade("m08.c11.symptom", "m08.c11.checklist", Predictions.C11_SYMPTOM, Predictions.C11_CHECKLIST, d, "R10,R8",
                    "d5417e37e65ab23f2780cba3ec46c69b4d0f8f7aed032f757232f68b2e5f5c46");
        }
    }

    @Test
    void c12() {
        try (C12Probe p = new C12Probe()) {
            Diagnosis d = p.run(new C12OrderEvents(p.orderDb, p.analyticsClient, p.io)::place);
            grade("m08.c12.symptom", "m08.c12.checklist", Predictions.C12_SYMPTOM, Predictions.C12_CHECKLIST, d, "R6,R9",
                    "908ea7c386e4f3c56ba8802acbac5df22c043f1ced056671b40278cc29e82f7a");
        }
    }

    @Test
    void c13() {
        try (C13Probe p = new C13Probe()) {
            Diagnosis d = p.run(new C13Fulfillment(p.inventoryClient, p.orderClient, p.paymentClient)::fulfill);
            grade("m08.c13.symptom", "m08.c13.checklist", Predictions.C13_SYMPTOM, Predictions.C13_CHECKLIST, d, "R13",
                    "851fee8182f9d73187048306b1b55c2bdc79463dd750f5fa60f6ff614ce417a9");
        }
    }

    @Test
    void c14() {
        try (C14Probe p = new C14Probe()) {
            Diagnosis d = p.run(new C14SalesReport(p.reportDb, p.reportPool)::salesReport);
            grade("m08.c14.symptom", "m08.c14.checklist", Predictions.C14_SYMPTOM, Predictions.C14_CHECKLIST, d, "R10,R11",
                    "a93acd1f25805763bc1662d5492c68589cedaa1ee6f7c7a05e7e7bd9bec7cea6");
        }
    }

    @Test
    void c15() {
        try (C15Probe p = new C15Probe()) {
            Diagnosis d = p.run(new C15ProductSearch(p.searchDb, p.io)::search);
            grade("m08.c15.symptom", "m08.c15.checklist", Predictions.C15_SYMPTOM, Predictions.C15_CHECKLIST, d, "R1,R2,R11",
                    "a6a4f9e209d4ae800a43cebb908494b4e40e9a7fb159978bb34bd71180939892");
        }
    }

    @Test
    void c16() {
        try (C16Probe p = new C16Probe()) {
            Diagnosis d = p.run(new C16OrderStatus(p.orderDb, p.deliveryClient, p.mdcAwareIo)::status);
            grade("m08.c16.symptom", "m08.c16.checklist", Predictions.C16_SYMPTOM, Predictions.C16_CHECKLIST, d, "R1,R12",
                    "2afe0e3cf322ff28187595c553128ebea9c4de47a7fe6b1de119c5d09f7225a7");
        }
    }

    @Test
    void c17() {
        try (C17Probe p = new C17Probe()) {
            Diagnosis d = p.run(new C17OrderDetail(p.orderClient, p.legacyDb, p.jdbcPool)::detail);
            grade("m08.c17.symptom", "m08.c17.checklist", Predictions.C17_SYMPTOM, Predictions.C17_CHECKLIST, d, "R1,R3,R4",
                    "bed07cefe032c04aa07b2e7d334ba4bc3ef4521234a303fe6eba10a291bb6e5d");
        }
    }

    @Test
    void c18() {
        try (C18Probe p = new C18Probe()) {
            Diagnosis d = p.run(new C18Checkout(p.orderDb, p.inventoryDb, p.taskExecutor)::checkout);
            grade("m08.c18.symptom", "m08.c18.checklist", Predictions.C18_SYMPTOM, Predictions.C18_CHECKLIST, d, "R11,R5",
                    "58d3513ad292403d75a82418e3ca05c07e1ae6cc6c6f8477e9733e6f40d2ccb0",
                    "8961abcf9c922248e2833ce03f448dadf027a58cb8d3281415bdd53983d9134e");
        }
    }

    @Test
    void c19() {
        try (C19Probe p = new C19Probe(); C19ProductCard bug = new C19ProductCard(p.catalogClient, p.priceDb)) {
            Diagnosis d = p.run(bug::card);
            grade("m08.c19.symptom", "m08.c19.checklist", Predictions.C19_SYMPTOM, Predictions.C19_CHECKLIST, d, "R3,R11",
                    "e97b29f66b27d60edecf3e54c79dac07e3320fc54d25c85d97dea1ce1323b5f4");
        }
    }

    @Test
    void c20() {
        try (C20Probe p = new C20Probe()) {
            Diagnosis d = p.run(new C20OrderExport(p.exportDb, p.pool)::export);
            grade("m08.c20.symptom", "m08.c20.checklist", Predictions.C20_SYMPTOM, Predictions.C20_CHECKLIST, d, "R12",
                    "32b30cff7820d02017e534eb93bc49ce257b7aa33bd98691779b30d61a736afd");
        }
    }

    @Test
    void c21() {
        try (C21Probe p = new C21Probe()) {
            Diagnosis d = p.run(new C21PriceCache(p.priceClient)::price);
            grade("m08.c21.symptom", "m08.c21.checklist", Predictions.C21_SYMPTOM, Predictions.C21_CHECKLIST, d, "R10,R6",
                    "c8421e12dfa866d08d3510d94b9cc3b7cb8b70946b846d95c5c9190e94fafde4");
        }
    }

    @Test
    void c22() {
        try (C22Probe p = new C22Probe()) {
            Diagnosis d = p.run(new C22PaymentCancel(p.pgClient, p.refundDb, p.mdcAwareIo)::cancel);
            grade("m08.c22.symptom", "m08.c22.checklist", Predictions.C22_SYMPTOM, Predictions.C22_CHECKLIST, d, "R1,R12",
                    "06ba979af3d0be3904f4f5f6f2c0b5f22f09c8324e229b26066026252c63439e",
                    "8a39b7b598c9bc361b1a06d73479719e982f7976bb65cdc63ff615097fcf80a5");
        }
    }
}
