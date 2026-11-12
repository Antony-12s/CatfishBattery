package com.catfish.battery;

/** Window-sized presentation only. The original pack geometry, telemetry and page actions stay intact. */
final class BatteryWindowStyle {
    private BatteryWindowStyle() { }

    static String css() {
        return """
            :root{--bg:#20272e;--bg-deep:#0b1118;--surface:#111a24;--surface-2:#172230;
              --surface-3:#263445;--line:#304255;--line-soft:#263445;--text:#e8eef5;
              --muted:#a7b6c8;--muted-2:#9aaabd;--accent:#2a74d6;--accent-2:#79b5ff;--radius:24px}
            html[data-theme='daylight']{--bg:#edf2f7;--bg-deep:#e7eef5;--surface:#fff;
              --surface-2:#f0f5fa;--surface-3:#c8d5e2;--line:#b8c7d7;--line-soft:#d5dfe9;
              --text:#142a40;--muted:#445d75;--muted-2:#4d647a;--accent:#2368c5;--accent-2:#205ca9}
            html,body{height:100%;min-height:0;background:transparent;overflow:hidden}
            body{font-size:22px;line-height:1.4;letter-spacing:0}
            main.page{width:100%;height:100%;margin:0;padding:0}
            .page>.card,.packLayout,.packCard{height:100%;min-height:0}
            .card{padding:24px 28px;border-radius:24px;display:flex;flex-direction:column}
            .sectionHead{flex:none;margin-bottom:20px;min-height:76px}
            .sectionHead h2{font-size:32px;font-weight:750}
            .sectionKicker{font-size:22px;margin-top:4px}
            .headActions>*+*,.flowActions>*+*{margin-left:18px}
            .liveBadge,.softBadge{font-size:21px;min-height:48px;padding:8px 18px}
            .liveBadge i{width:10px;height:10px;margin-right:8px}
            .compact,.ghost{min-height:76px;padding:10px 24px;font-size:22px;border-radius:16px}
            .packTabs,.rangeSelect{border-radius:20px;padding:6px;display:grid;grid-auto-flow:column;gap:23px}
            .packTabs .subtab,.rangeSelect button{min-height:76px;min-width:92px;margin:0!important;
              padding:12px 26px;font-size:24px;border-radius:15px}
            .packTabs .subtab.active,.rangeSelect button.active{background:var(--accent);color:#fff}
            button:active{filter:brightness(1.2);transform:scale(.98)}
            button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid var(--accent-2);outline-offset:3px}
            .packCaption{display:none}
            #packViewSchematic{display:flex;flex-direction:column;flex:1;min-height:0}
            #packViewSchematic[hidden],#packViewCells[hidden]{display:none}
            .packWrap{display:flex;align-items:center;justify-content:center;flex:1;min-height:0;overflow:visible;
              border-radius:20px;background:#0b1118}
            /* SVG and its HTML flow dots share this exact, aspect-preserving box. */
            .packLayout.isOffline .packWrap,.packLayout.isOffline .legend,.packLayout.isOffline .pack4{opacity:1}
            .packBox{flex:none;max-width:100%;width:100%}
            .packWrap svg{width:100%;min-width:0;height:auto}
            .packTip{font-size:24px;padding:12px 18px}
            .legend{flex:none;margin-top:14px;gap:12px 24px}
            .scale{font-size:20px}.swatches span{font-size:19px;margin-right:16px}
            .swatches i{width:16px;height:12px}
            .facts{flex:none;margin-top:18px;gap:18px;grid-template-columns:3fr 3fr 4.5fr}
            .cellFacts{border-radius:18px}
            .cellFacts>div{padding:16px 20px}
            .cellFacts small{font-size:22px}
            .cellFacts strong{font-size:32px;font-weight:750}
            .cellFacts em{font-size:18px}
            #packViewCells{flex:1;min-height:0;display:flex;flex-direction:column}
            .pack4{flex:1;min-height:0;grid-template-columns:36px minmax(0,1fr);gap:16px}
            .front span{font-size:16px}
            .mods{display:grid;grid-template-rows:repeat(2,minmax(0,1fr));gap:18px;min-height:0}
            .prow4{margin:0;gap:18px;min-height:0;grid-template-columns:repeat(2,minmax(0,1fr))}
            .blk{min-height:0;display:flex;flex-direction:column;padding:16px 18px;border-radius:20px}
            .bh{flex:none;margin-bottom:10px}.bh>div{display:flex;align-items:baseline}
            .bh strong{font-size:28px;margin-right:18px}.bh small{font-size:19px}
            .mcg{gap:8px}.mcg10{flex:1;min-height:0;grid-template-columns:repeat(10,minmax(0,1fr));grid-template-rows:repeat(3,minmax(0,1fr))}
            .mcg12{grid-template-columns:repeat(12,minmax(0,1fr))}
            .mc{min-width:0;min-height:0;padding:8px 10px;border-radius:12px;display:flex;flex-direction:column;justify-content:center}
            .mc i{font-size:18px}.mc b{font-size:28px}.mc em{font-size:17px;top:8px;right:10px}
            .cellInfoHead{flex:none;margin:12px 0 8px}.cellInfoHead .sectionKicker{font-size:22px}
            .cellInfoHead small{font-size:19px}#cellTempGrid{flex:none}.mc.st{min-height:76px}
            .flowStats{flex:none;margin-bottom:20px;border-radius:20px}
            .flowStats>div{padding:18px 24px}
            .flowStats small{font-size:22px}.flowStats strong{font-size:38px;font-weight:750}.flowStats em{font-size:19px}
            .histMain{flex:1;min-height:0;grid-template-columns:minmax(0,1fr) 580px;grid-template-rows:minmax(0,1fr);gap:24px}
            .histChart{min-height:0;display:flex;flex-direction:column;padding:20px;border:1px solid var(--line);border-radius:20px;background:var(--bg-deep)}
            .histMain .historyChartWrap{height:auto;flex:1;min-height:0}
            .histLegend{flex:none;font-size:20px;margin-top:16px;line-height:1.8}
            .histSide{padding:24px;border-radius:20px}.sideTitle{font-size:26px}
            .histSideHead{flex:none;flex-wrap:wrap;row-gap:10px}
            .batteryFact{padding:14px 0}.batteryFact>span{font-size:21px}.batteryFact strong{font-size:32px}
            .chargeCurve{height:130px}.batteryFact .costLine{font-size:20px}
            .histSide .helper,.helper{font-size:24px;line-height:1.6}
            .crow{padding:20px 0;gap:12px}.cwhen b{font-size:24px}.cwhen small,.cmeta{font-size:20px}
            .crow .cost{font-size:26px}.tag{width:60px;height:42px;font-size:20px}
            .kpis{flex:none;gap:24px;margin-bottom:24px}
            .kpi{padding:28px;border-radius:20px}.kpi small{font-size:24px}.kpi strong{font-size:48px}
            .kpi strong em,.kpi em.sub{font-size:22px}
            .sessCard .list{flex:1;min-height:0;overflow:auto;border-radius:20px}
            .list .helper{display:flex;align-items:center;justify-content:center;height:100%;margin:0 auto;max-width:1000px;text-align:center;padding:40px}
            .hint{flex:none;font-size:20px;line-height:1.5;margin-top:18px}
            .mhead{padding:22px}.mhead b{font-size:28px}.mhead span{font-size:22px}
            .hrow{min-height:100px;gap:20px;padding:18px 22px;grid-template-columns:70px 1.25fr 1.3fr .8fr .8fr 1fr .7fr}
            .hwhen b{font-size:26px}.hwhen small,.soc small,.peak{font-size:22px}
            .kwh,.cost{font-size:28px}.chip{min-height:70px;font-size:22px}
            .two{flex:1;min-height:0;align-items:stretch;gap:24px;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr)}
            .pane{padding:26px;border-radius:20px;min-width:0;min-height:0;display:flex;flex-direction:column}
            .pane h3{font-size:28px;margin:0 0 16px}
            .pane h3 small{display:block;font-size:20px;margin:6px 0 0}
            .setChargers{flex:1;min-height:0;overflow:auto}
            .chargerRow{grid-template-columns:minmax(200px,1.2fr) 152px minmax(140px,1fr) minmax(140px,1fr) 76px;gap:18px;padding:20px 0}
            .pane input,.pane select{min-height:76px;font-size:26px;border-radius:14px;padding-left:18px}
            .cMain{gap:14px}.defs{display:grid;grid-template-columns:1fr 1fr;gap:23px}
            .defBtn{min-height:76px;margin:0;padding:8px 10px;font-size:19px;justify-content:center;text-align:center;border-radius:14px}
            .cTou{min-height:76px;padding:0 12px;font-size:21px;justify-content:center;border-radius:14px}
            .cTou i{flex:none;width:46px;outline:1px solid var(--muted)}
            .cTou input{min-height:0;padding:0}
            .cField input{padding-right:78px}.cField em{font-size:18px}.cField i{font-size:19px;top:-14px}
            .cDel{width:76px;height:76px;font-size:26px;border-radius:14px}
            .addc{flex:none;align-self:flex-start;min-height:76px;padding:0 28px;font-size:24px;margin-top:20px;border-radius:16px}
            .prow{flex:1;gap:20px;padding:16px 0;grid-template-columns:minmax(0,1fr) auto}
            .prow b{font-size:26px}.prow small{font-size:20px;max-width:400px}
            .prow select{min-width:210px}
            .field input{width:175px;padding-right:70px;font-size:28px}.field em{font-size:19px}
            .savebar{flex:none;margin-top:24px;padding:12px 18px;border-radius:18px}
            .savebar .setSave{min-height:76px;min-width:220px;font-size:28px;border-radius:14px;background:var(--accent)}
            .setState{font-size:24px}
            .pickCard{width:min(1000px,calc(100% - 96px));max-height:85vh;padding:32px;border-radius:24px}
            .pickHead b{font-size:32px}.pickList{gap:23px}.pickList button{min-height:90px;padding:20px 28px;font-size:28px}.pickList small{font-size:24px}
            .toastRegion{width:560px;bottom:28px;right:28px}.toast{padding:20px;font-size:24px}
            /* Freshness + readability polish for the standalone window. The pack SVG geometry is untouched. */
            .updBadge{display:inline-flex;align-items:center}
            .chartAxis{font-size:16px;font-weight:700}
            .chartAxisKw{font-weight:800}
            .chargeCurve .chartAxis{font-size:15px}
            .chartEmpty{font-size:22px}
            .cellMark{font-size:13px}
            .emptyState{max-width:1000px}.emptyState b{font-size:32px}.emptyState span{font-size:24px}
            /* The diagram keeps its original dark substrate so the original SVG labels stay legible in daylight too. */
            @media(max-width:1900px){body{font-size:20px}.card{padding:18px 22px}.sectionHead h2{font-size:28px}
              .sectionKicker{font-size:20px}.headActions>*+*{margin-left:12px}.softBadge{font-size:18px;padding:6px 12px}
              .histMain{grid-template-columns:minmax(0,1fr) 440px}.cellFacts strong{font-size:26px}.cellFacts>div{padding:12px}
              .chargerRow{grid-template-columns:minmax(140px,1fr) 120px minmax(110px,1fr) minmax(110px,1fr) 76px;gap:12px}
              .two{grid-template-columns:minmax(0,1.2fr) minmax(0,1fr)}.field input{width:140px}.prow b{font-size:22px}.prow small{font-size:18px}}
            @media(max-height:950px){.sectionHead{min-height:64px;margin-bottom:12px}.facts{margin-top:12px}
              .cellFacts>div{padding-top:10px;padding-bottom:10px}.legend{margin-top:8px}.pane{padding:18px}
              .cellInfoHead{margin-top:8px}.blk{padding:10px 14px}.bh{margin-bottom:6px}.mc{padding:4px 8px}}
            """;
    }

    static String script() {
        return """
            // Fit by both axes, not a fixed width cap. No stretch: the schematic keeps its viewBox aspect (pack geometry unchanged).
            function fitPackBox(){
              const wrap=document.querySelector('.packWrap'),box=document.querySelector('.packBox');
              if(!wrap||!wrap.clientHeight)return;
              const width=Math.min(wrap.clientWidth-2,(wrap.clientHeight-2)*PACK_RATIO);
              box.style.width=Math.max(0,width)+'px';
              if(typeof moverKeys==='function')moverKeys();
            }
            if(document.querySelector('.packWrap')){
              const packResize=new ResizeObserver(fitPackBox);
              packResize.observe(document.querySelector('.packWrap'));
              fitPackBox();
            }
            """.replace("PACK_RATIO", BatteryWebPages.VIEW_W + "/" + BatteryWebPages.VIEW_H);
    }
}
