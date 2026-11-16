(()=>{ 
  const getOpenMenus=()=>Array.from(document.querySelectorAll('.nav-tools[open]'));
  document.addEventListener('click',event=>{
    getOpenMenus().forEach(menu=>{
      if(!menu.contains(event.target)) menu.open=false;
    });
  });
  document.addEventListener('keydown',event=>{
    if(event.key!=='Escape')return;
    const menus=getOpenMenus();
    if(!menus.length)return;
    menus.forEach(menu=>menu.open=false);
    menus[menus.length-1].querySelector('summary')?.focus();
  });
})();
