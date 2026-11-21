# Roteiro de entrevista com a Angel Signature (Anjos Tecnologia)

Levantamento simplificado de necessidades do Projeto Integrador (item A do trabalho).
O objetivo é entender **por que** e **onde** a aplicação serve ao dia a dia da empresa,
e registrar as respostas para o relatório técnico.

| | |
|---|---|
| **Entrevistado(a)** | |
| **Cargo / função** | |
| **Data** | |
| **Formato** | ( ) presencial ( ) videochamada ( ) mensagens |
| **Entrevistadores** | |

> Dica: não é preciso responder tudo. As perguntas marcadas com ⭐ são as mais
> importantes para o relatório. Anote as respostas com as palavras do entrevistado.

---

## 1. Sobre a empresa

1. ⭐ Qual é a área de atuação e o que a empresa faz? Quem são os clientes?
   - **Resposta:**
2. Quantas pessoas trabalham na empresa e quantas lidam com documentos e contratos?
   - **Resposta:**
3. A empresa tem equipe de TI ou o suporte é terceirizado?
   - **Resposta:**

## 2. Situação atual (como é hoje)

4. ⭐ Como os documentos (contratos, termos, propostas) são enviados para assinatura hoje?
   (papel, e-mail com PDF, WhatsApp, outro serviço pago)
   - **Resposta:**
5. ⭐ Quantos documentos são assinados por mês, em média? Quantas pessoas assinam cada um?
   - **Resposta:**
6. Quanto tempo leva, em média, entre o envio e a última assinatura?
   - **Resposta:**
7. Onde os documentos assinados ficam guardados? Como são consultados depois?
   - **Resposta:**
8. Já usam alguma ferramenta de assinatura eletrônica? Qual, e quanto custa por mês?
   - **Resposta:**

## 3. O problema

9. ⭐ Qual a maior dificuldade nesse processo hoje?
   - **Resposta:**
10. Já houve documento perdido, assinado errado, adulterado ou assinado duas vezes?
    - **Resposta:**
11. Já houve dificuldade para comprovar **quem** assinou, **quando** e de **onde**?
    - **Resposta:**
12. O que acontece quando o signatário não tem certificado digital nem conta em plataforma?
    - **Resposta:**

## 4. Aplicabilidade da solução proposta

> Apresentar em uma frase: *"O emissor envia o PDF, cada pessoa recebe um link
> único por e-mail, assina pelo navegador sem criar conta, e o sistema gera o PDF
> final com página de auditoria e selo digital."*

13. ⭐ Esse fluxo resolveria o problema descrito? Em quais situações a empresa usaria?
    - **Resposta:**
14. ⭐ Quem seria o **emissor** (quem cria e envia documentos)? E quem seriam os signatários?
    - **Resposta:**
15. A assinatura desenhada na tela, com registro de data, IP e navegador, é suficiente
    para os documentos da empresa? Algum exige certificado ICP-Brasil?
    - **Resposta:**
16. A página de auditoria (quem assinou, quando, IP, hash do documento) é útil? Falta alguma informação?
    - **Resposta:**
17. O compartilhamento de arquivos pelo servidor (listar e baixar documentos finais) atende
    à necessidade de consulta depois de assinados?
    - **Resposta:**
18. ⭐ O que precisaria existir para a empresa realmente adotar a solução?
    - **Resposta:**

## 5. Segurança e privacidade

19. ⭐ Que tipo de informação aparece nos documentos? (dados pessoais, financeiros, contratos sigilosos)
    - **Resposta:**
20. Há exigência de proteção de dados (LGPD) ou de guarda dos documentos por determinado prazo?
    - **Resposta:**
21. Quem pode ter acesso aos documentos assinados? Deve haver controle por pessoa?
    - **Resposta:**
22. Os links de assinatura valem por 7 dias e só funcionam uma vez. Esse prazo é adequado?
    - **Resposta:**
23. A comunicação cifrada (HTTPS/TLS) e a trilha de auditoria atendem às expectativas de segurança?
    - **Resposta:**

## 6. Limites e expectativas

24. ⭐ O que a solução **não** precisa fazer? (para evitar funcionalidades desnecessárias)
    - **Resposta:**
25. O e-mail de convite é enviado por um serviço externo (Resend). Há restrição ou preferência de provedor?
    - **Resposta:**
26. O certificado usado hoje é de **teste**. A empresa pretende usar um certificado
    A1 ICP-Brasil real no futuro?
    - **Resposta:**
27. Que melhorias a empresa gostaria de ver depois do protótipo?
    - **Resposta:**

## 7. Validação (preencher depois da demonstração)

28. ⭐ O fluxo demonstrado atende à necessidade? (nota de 1 a 5)
    - **Nota:**  **Comentário:**
29. O que funcionou bem?
    - **Resposta:**
30. O que foi confuso ou faltou?
    - **Resposta:**
31. Quais sugestões a empresa deixa para o projeto?
    - **Resposta:**
32. Autoriza citar o nome da empresa e as considerações no relatório?
    - ( ) Sim ( ) Não

---

## Como usar as respostas no relatório

| Seção do relatório | Perguntas de apoio |
|---|---|
| 1. Identificação da instituição | 1, 2, 3 |
| 2. Problema identificado | 4 a 12 |
| 3. Solução desenvolvida | 13 a 23 |
| 4. Resultados (demonstração) | 28 a 32 |
| 5. Considerações finais | 24 a 27, 30, 31 |
