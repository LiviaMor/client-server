package sica.assinatura.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limitador de taxa simples, em memoria, por chave (ex.: IP), com janela fixa.
 *
 * <p>Suficiente para um MVP com uma unica instancia. Com varias instancias,
 * seria preciso um armazenamento compartilhado.</p>
 */
final class LimitadorTaxa {

    /** Acima disso, janelas vencidas sao descartadas para conter o uso de memoria. */
    private static final int LIMPAR_ACIMA_DE = 10_000;

    private final int maximo;
    private final long janelaMillis;
    private final LongSupplier relogio;
    private final ConcurrentHashMap<String, Janela> janelas = new ConcurrentHashMap<>();

    private static final class Janela {
        final long inicio;
        int contagem;

        Janela(long inicio) {
            this.inicio = inicio;
        }
    }

    LimitadorTaxa(int maximo, long janelaMillis) {
        this(maximo, janelaMillis, System::currentTimeMillis);
    }

    LimitadorTaxa(int maximo, long janelaMillis, LongSupplier relogio) {
        this.maximo = maximo;
        this.janelaMillis = janelaMillis;
        this.relogio = relogio;
    }

    /** Conta uma requisicao; devolve {@code false} se o limite foi excedido. */
    boolean permitir(String chave) {
        long agora = relogio.getAsLong();
        if (janelas.size() > LIMPAR_ACIMA_DE) {
            janelas.values().removeIf(j -> agora - j.inicio >= janelaMillis);
        }
        Janela j = janelas.compute(chave, (k, atual) ->
                atual == null || agora - atual.inicio >= janelaMillis ? new Janela(agora) : atual);
        synchronized (j) {
            return ++j.contagem <= maximo;
        }
    }

    /** Segundos ate a janela atual da chave terminar (para {@code Retry-After}). */
    long segundosParaLiberar(String chave) {
        Janela j = janelas.get(chave);
        if (j == null) {
            return 0;
        }
        long resto = janelaMillis - (relogio.getAsLong() - j.inicio);
        return Math.max(1, (resto + 999) / 1000);
    }
}
