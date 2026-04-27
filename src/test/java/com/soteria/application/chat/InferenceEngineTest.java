package com.soteria.application.chat;

import com.soteria.core.domain.emergency.Protocol;
import com.soteria.core.port.KnowledgeBase;
import com.soteria.core.port.Triage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InferenceEngine Logic Tests")
class InferenceEngineTest {

    @Test
    @DisplayName("Should build context from protocol matches")
    void buildContext() {
        Protocol p = new Protocol();
        p.setId("ID1");
        p.setTitle("Title");
        p.setCategory("Medical");
        p.setSteps(List.of("Step 1"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "Vector", 0.95f);

        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("ID1"));
        assertTrue(context.contains("Title"));
        assertTrue(context.contains("Step 1"));
    }

    @Test
    @DisplayName("Should handle empty matches in context builder")
    void buildEmptyContext() {
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(), null, null);
        assertEquals("No specific protocol matched.", context);
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Spanish")
    void testMultilingualManifestSpanish() {
        Protocol p = new Protocol();
        p.setId("ES_01");
        p.setTitle("Paro Card\u00edaco");
        p.setSteps(List.of("Realizar RCP", "Llamar al 112"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "CLASSIFIER", 0.99f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);
        System.out.print("HEX DUMP: ");
        for (char c : context.toCharArray()) System.out.printf("\\u%04x ", (int)c);
        System.out.println();
        String expected = "Paro Card\u00edaco";
        assertTrue(context.contains(expected), "Manifest should contain: " + expected + "\nActual: " + context);
        assertTrue(context.contains("Realizar RCP"));
        assertTrue(context.contains("Llamar al 112"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Japanese (CJK)")
    void testMultilingualManifestJapanese() {
        Protocol p = new Protocol();
        p.setId("JA_01");
        p.setTitle("\u706b\u707d");
        p.setSteps(List.of("\u907f\u96e3\u3057\u3066\u304f\u3060\u3055\u3044", "119\u756a\u306b\u96fb\u8a71\u3057\u3066\u304f\u3060\u3055\u3044"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.88f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);
        System.out.println("DEBUG JAPANESE CONTEXT: " + context);
        assertTrue(context.contains("\u706b\u707d"));
        assertTrue(context.contains("\u907f\u96e3\u3057\u3066\u304f\u3060\u3055\u3044"));
        assertTrue(context.contains("119\u756a\u306b\u96fb\u8a71\u3057\u3066\u304f\u3060\u3055\u3044"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Arabic (Afroasiatic)")
    void testMultilingualManifestArabic() {
        Protocol p = new Protocol();
        p.setId("AR_01");
        p.setTitle("حالة طبية طارئة");
        p.setSteps(List.of("اتصل بالإسعاف", "ابق هادئا"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.90f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("حالة طبية طارئة"));
        assertTrue(context.contains("اتصل بالإسعاف"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Hindi (Indo-Aryan)")
    void testMultilingualManifestHindi() {
        Protocol p = new Protocol();
        p.setId("HI_01");
        p.setTitle("आपातकालीन चिकित्सा");
        p.setSteps(List.of("एम्बुलेंस बुलाओ", "शांत रहो"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.92f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("आपातकालीन चिकित्सा"));
        assertTrue(context.contains("एम्बुलेंस बुलाओ"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Chinese (Sino-Tibetan)")
    void testMultilingualManifestChinese() {
        Protocol p = new Protocol();
        p.setId("ZH_01");
        p.setTitle("緊急救援");
        p.setSteps(List.of("撥打120", "保持冷靜"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.95f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("緊急救援"));
        assertTrue(context.contains("撥打120"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Russian (Slavic)")
    void testMultilingualManifestRussian() {
        Protocol p = new Protocol();
        p.setId("RU_01");
        p.setTitle("Скорая помощь");
        p.setSteps(List.of("Вызовите скорую", "Сохраняйте спокойствие"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.91f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("Скорая помощь"));
        assertTrue(context.contains("Вызовите скорую"));
    }

    @Test
    @DisplayName("Contextual Query Preparation: Multilingual")
    void testPrepareContextualQuery() {
        InferenceEngine engine = new InferenceEngine(null, null, null);
        com.soteria.core.domain.chat.ChatSession session = new com.soteria.core.domain.chat.ChatSession();
        
        // Spanish context turn
        session.getCategorizedContext().put("MEDICAL", new java.util.ArrayList<>(List.of("Me duele el pecho")));
        
        String query = engine.prepareContextualQuery("I am dizzy", session);
        
        assertTrue(query.contains("Me duele el pecho"));
        assertTrue(query.contains("I am dizzy"));
    }

    @Test
    @DisplayName("History Filtering Logic")
    void testFilterRelevantHistory() {
        InferenceEngine engine = new InferenceEngine(null, null, null);
        com.soteria.core.domain.chat.ChatSession session = new com.soteria.core.domain.chat.ChatSession();
        
        List<com.soteria.core.domain.chat.ChatMessage> history = List.of(
            com.soteria.core.domain.chat.ChatMessage.user("Greeting"),
            com.soteria.core.domain.chat.ChatMessage.model("Hello"),
            com.soteria.core.domain.chat.ChatMessage.user("My head hurts"),
            com.soteria.core.domain.chat.ChatMessage.model("Where exactly?"),
            com.soteria.core.domain.chat.ChatMessage.user("Temple area")
        );
        
        // Should keep recent turns (last 4 messages by default in InferenceEngine)
        List<com.soteria.core.domain.chat.ChatMessage> filtered = engine.filterRelevantHistory(history, "Temple area", session);
        
        // "Greeting" and its response should be filtered out if they are not in relevantTurns
        assertFalse(filtered.stream().anyMatch(m -> m.content().equals("Greeting")));
        assertTrue(filtered.stream().anyMatch(m -> m.content().equals("My head hurts")));
        assertTrue(filtered.stream().anyMatch(m -> m.content().equals("Temple area")));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Swahili (Bantu)")
    void testMultilingualManifestSwahili() {
        Protocol p = new Protocol();
        p.setId("SW_01");
        p.setTitle("Dharura ya Moto");
        p.setSteps(List.of("Ondoka kwenye jengo", "Piga simu ya zimamoto"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.95f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("Dharura ya Moto"));
        assertTrue(context.contains("Ondoka kwenye jengo"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Turkish (Turkic)")
    void testMultilingualManifestTurkish() {
        Protocol p = new Protocol();
        p.setId("TR_01");
        p.setTitle("Kalp Krizi");
        p.setSteps(List.of("Sakin kal\u0131n", "Ambulans \u00e7a\u011fr\u0131n"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "CLASSIFIER", 0.92f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("Kalp Krizi"));
        assertTrue(context.contains("Sakin kal\u0131n"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Vietnamese (Austroasiatic)")
    void testMultilingualManifestVietnamese() {
        Protocol p = new Protocol();
        p.setId("VI_01");
        p.setTitle("C\u1ea5p c\u1ee9u");
        p.setSteps(List.of("G\u1ecdi s\u1ed1 115", "Th\u1ef1c hi\u1ec7n h\u00f4 h\u1ea5p nh\u00e2n t\u1ea1o"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.85f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("C\u1ea5p c\u1ee9u"));
        assertTrue(context.contains("G\u1ecdi s\u1ed1 115"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Korean (Koreanic)")
    void testMultilingualManifestKorean() {
        Protocol p = new Protocol();
        p.setId("KO_01");
        p.setTitle("\uc751\uae09 \uc0c1\ud669");
        p.setSteps(List.of("119\uc5d0 \uc804\ud654\ud558\uc138\uc694", "\uc2ec\ud3d0\uc18c\uc0dd\uc220 \uc2e4\uc2dc"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.99f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("\uc751\uae09 \uc0c1\ud669"));
        assertTrue(context.contains("119\uc5d0 \uc804\ud654\ud558\uc138\uc694"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Bengali (Indo-Aryan)")
    void testMultilingualManifestBengali() {
        Protocol p = new Protocol();
        p.setId("BN_01");
        p.setTitle("জরুরি অবস্থা");
        p.setSteps(List.of("অ্যাম্বুলেন্স ডাকুন", "শান্ত থাকুন"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.95f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("জরুরি অবস্থা"));
        assertTrue(context.contains("অ্যাম্বুলেন্স ডাকুন"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Portuguese (Romance)")
    void testMultilingualManifestPortuguese() {
        Protocol p = new Protocol();
        p.setId("PT_01");
        p.setTitle("Emergência Médica");
        p.setSteps(List.of("Ligue para o 112", "Mantenha a calma"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "CLASSIFIER", 0.98f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("Emergência Médica"));
        assertTrue(context.contains("Ligue para o 112"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Telugu (Dravidian)")
    void testMultilingualManifestTelugu() {
        Protocol p = new Protocol();
        p.setId("TE_01");
        p.setTitle("అత్యవసర పరిస్థితి");
        p.setSteps(List.of("అంబులెన్స్‌ను పిలవండి", "ప్రశాంతంగా ఉండండి"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.90f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("అత్యవసర పరిస్థితి"));
        assertTrue(context.contains("అంబులెన్స్‌ను పిలవండి"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Amharic (Semitic/Afroasiatic)")
    void testMultilingualManifestAmharic() {
        Protocol p = new Protocol();
        p.setId("AM_01");
        p.setTitle("አስቸኳይ ሁኔታ");
        p.setSteps(List.of("አምቡላንስ ይደውሉ", "ተረጋጋ"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.92f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("አስቸኳይ ሁኔታ"));
        assertTrue(context.contains("አምቡላንስ ይደውሉ"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Finnish (Uralic)")
    void testMultilingualManifestFinnish() {
        Protocol p = new Protocol();
        p.setId("FI_01");
        p.setTitle("Hätätilanne");
        p.setSteps(List.of("Soita hätänumeroon", "Pysy rauhallisena"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.94f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("Hätätilanne"));
        assertTrue(context.contains("Soita hätänumeroon"));
    }

    @Test
    @DisplayName("Multilingual Manifest Support: Greek (Hellenic)")
    void testMultilingualManifestGreek() {
        Protocol p = new Protocol();
        p.setId("EL_01");
        p.setTitle("Επείγουσα κατάσταση");
        p.setSteps(List.of("Καλέστε το 166", "Μείνετε ψύχραιμοι"));

        KnowledgeBase.ProtocolMatch match = new KnowledgeBase.ProtocolMatch(p, "VECTOR", 0.96f);
        InferenceEngine engine = new InferenceEngine(null, null, null);
        String context = engine.buildProtocolManifest(List.of(match), null, null);

        assertTrue(context.contains("Επείγουσα κατάσταση"));
        assertTrue(context.contains("Καλέστε το 166"));
    }
}
