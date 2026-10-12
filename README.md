<div align="center">

# WonderTranslate

### Tradução automática para Aliucord, com um toque.

![Versão](https://img.shields.io/badge/VERSÃO-0.1.5-2563EB?style=for-the-badge)
![Estado](https://img.shields.io/badge/ESTADO-DESENVOLVIMENTO-F59E0B?style=for-the-badge)
![Plataforma](https://img.shields.io/badge/PLATAFORMA-ANDROID-3DDC84?style=for-the-badge)

**Mensagens em outros idiomas, exibidas dentro do chat.**

**[⬇️ Baixar WonderTranslate 0.1.5](https://github.com/wonderkast02/WonderTranslate/releases/download/v0.1.5/WonderTranslate-0.1.5.zip)** · [Código-fonte](plugins/WonderTranslate/src/main/kotlin/dev/wonderkast/wondertranslate)

</div>

---

> [!WARNING]
> **Pré-lançamento experimental, não uma versão estável.** Funcional no dispositivo testado; a compatibilidade com outras versões do Aliucord não está garantida.

## ✨ Recursos

- **🌐 ON/OFF:** um toque ativa ou desativa a tradução, inclusive em canais somente leitura.
- **Automático:** traduz as mensagens já carregadas e as novas mensagens; o histórico mais antigo é processado conforme for carregado ao rolar.
- **Idiomas:** detecção automática da origem, português brasileiro como destino padrão e opção de trocar o idioma.
- **Texto original preservado:** a tradução aparece somente no seu aparelho; não envia mensagens ao Discord.

## ⬇️ Instalação

1. Use o **Aliucord Legacy** no Android (base Discord `126021`; Aliucord `2.9.1` ou compatível).
2. Baixe o [ZIP do plugin](https://github.com/wonderkast02/WonderTranslate/releases/download/v0.1.5/WonderTranslate-0.1.5.zip) e copie-o como `WonderTranslate.zip` para a pasta `Aliucord/plugins` do armazenamento interno.
3. Abra ou reinicie o Aliucord. Toque em **🌐 OFF** para ativar e aceite o aviso de tradução externa. Segure 🌐 para escolher o idioma.

> **Privacidade:** quando você autoriza a tradução, o conteúdo das mensagens traduzidas é enviado a `translate.googleapis.com` por uma interface não oficial, sujeita a mudanças e limites. Evite usar em conversas sensíveis. Anexos, imagens e textos de histórico ainda não carregado não são traduzidos.

**SHA-256 do ZIP 0.1.5:** `e453ef4162206ecdd3f845b42ad6e022830880733e80e719dad196108dbf4df9`

## 🛠️ Compilação

Java 21 + Android SDK 36. O script usa o [template oficial do Aliucord](https://github.com/Aliucord/plugins-template):

```bash
bash build_from_template.sh
```

O ZIP resultante fica em `.build/aliucord-template/plugins/WonderTranslate/build/outputs/WonderTranslate.zip`.

---

<div align="center">

**WonderTranslate · by [wonderkast02](https://github.com/wonderkast02)**

Feito para a comunidade Aliucord. Referências técnicas: [Aliucord](https://github.com/Aliucord) · [Translate](https://github.com/TymanWasTaken/aliucord-plugins/tree/master/Translate).

*Projeto independente, não oficial do Discord, Aliucord ou Google.*

</div>
