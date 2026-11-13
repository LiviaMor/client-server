package sica.assinatura.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TokenServicoTest {

    @Test
    void tokenTem256BitsEmBase64UrlSemPadding() {
        TokenServico.Token t = TokenServico.gerar();
        assertEquals(43, t.valor().length());
        assertTrue(t.valor().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(64, t.hash().length());
    }

    @Test
    void hashEhDeterministicoEDiferenteDoValor() {
        TokenServico.Token t = TokenServico.gerar();
        assertEquals(t.hash(), TokenServico.hash(t.valor()));
        assertFalse(t.hash().contains(t.valor()));
    }

    @Test
    void tokensSaoUnicos() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(vistos.add(TokenServico.gerar().valor()));
        }
    }

    @Test
    void toStringNaoExpoeOValor() {
        TokenServico.Token t = TokenServico.gerar();
        assertFalse(t.toString().contains(t.valor()));
    }
}
