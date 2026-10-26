# Phase 8.54 — Family Mode: scene vocabulary (his choice)

## Context
8.53's feed still leaked MovieBox scene titles with Family
Mode on: "Hot babe gets a BBC in front of her husband",
"Blonde babe Jessie Saint Craves…", "Pervert masseur being
inappropriate with client…", one pure-Japanese title. I
offered on-device photo detection (TFLite NSFW + blur) vs a
bigger keyword list; he chose the keyword list, then named
the missing words himself: "HOT babe, BBC, inappropriate —
these ARE adult words", "body massage and etc".

## Change (utils/FamilyMode.kt only)
Phrases added: hot babe, casting couch, cheating wife,
cheating husband, rated x, x rated, body massage,
full body massage, happy ending massage.

Words added (scene-title vocabulary): babe, babes, bbc, pov,
inappropriate, pervert, perverted, masseur, masseuse, nuru,
tantric, sensual, pornstar, pornhub, naughty, kinky, fetish,
bdsm, bondage, dominatrix, gloryhole, hardcore, softcore,
gonzo, ecchi, oppai, sekkusu, jav, onlyfans, cuckold,
swinger, swingers, foursome, uncensored, lewd, boobs, tits,
nipples, panties, hookup, bhabhi, aunty, stepmom, stepdad,
stepsis, stepbro, stepbrother, stepsister, stepfather,
stepmother, stepdaughter, stepson, stepfamily.
Stems added: seduc, stripper.

Non-Latin substrings added (raw match): セックス, おまんこ,
エッチ, ポルノ (Japanese), 色情, 成人电影, 无码, 有码 (Chinese) —
the leaked pure-Japanese title carries セックス, invisible
to any Latin list.

All four titles from his 8.53 screenshot now match: hot babe
(+bbc), babe, pervert/inappropriate/masseur, セックス.
