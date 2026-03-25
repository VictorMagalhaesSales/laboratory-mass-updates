-- 500.000 documentos em uma única operação (muito mais rápido que 500k INSERTs).
-- random() varia peso/valor/volume por linha; região alterna em ciclo para exercitar filtros/índices.

INSERT INTO documento_carga (numero_documento, status, peso, valor, volume, regiao, versao)
SELECT
    'DOC-' || lpad(gs::text, 10, '0'),
    'DISPONIVEL',
    round((random() * 9999 + 1)::numeric, 2),
    round((random() * 499999 + 1)::numeric, 2),
    round((random() * 999 + 1)::numeric, 2),
    (ARRAY['NORTE', 'SUL', 'LESTE', 'OESTE', 'CENTRO'])[1 + ((gs - 1) % 5)],
    1
FROM generate_series(1, 500000) AS gs;


INSERT INTO planejamento (usuario_id, status) 
SELECT gs, 'DRAFT' FROM generate_series(1, 10) gs;