package sica.assinatura.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class LimitadorTaxaTest {

    @Test
    void bloqueiaAcimaDoLimiteELiberaNaProximaJanela() {
        AtomicLong agora = new AtomicLong(1_000);
        LimitadorTaxa l = new LimitadorTaxa(3, 60_000, agora::get);

        assertTrue(l.permitir("1.1.1.1"));
        assertTrue(l.permitir("1.1.1.1"));
        assertTrue(l.permitir("1.1.1.1"));
        assertFalse(l.permitir("1.1.1.1"));
        assertTrue(l.permitir("2.2.2.2"), "outro IP tem a propria cota");

        agora.addAndGet(30_000);
        assertEquals(30, l.segundosParaLiberar("1.1.1.1"));

        agora.addAndGet(30_000);
        assertTrue(l.permitir("1.1.1.1"));
    }
}
