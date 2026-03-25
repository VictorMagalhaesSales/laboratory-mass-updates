INSERT INTO documento_carga (numero_documento, status, peso, valor, volume, regiao, versao)
SELECT
    'DOC-' || lpad(gs::text, 10, '0'),
    'DISPONIVEL',
    round((random() * 999 + 1)::numeric, 2),
    round((random() * 49999 + 1)::numeric, 2),
    round((random() * 99 + 1)::numeric, 2),
    'SUL',
    1
FROM generate_series(1, 1000) AS gs;

INSERT INTO planejamento (usuario_id, status)
SELECT gs, 'DRAFT' FROM generate_series(1, 20) gs;
