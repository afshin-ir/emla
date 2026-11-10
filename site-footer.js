(()=>{
  const phrase="طراحی سایت و توسعهٔ اپلیکیشن اَفرا توسط: افشین فلاطونی © ۱۴۰۵";
  let footer=document.querySelector('.site-footer');
  if(!footer){
    footer=document.createElement('footer');
    footer.className='site-footer';
    document.body.appendChild(footer);
  }
  let note=footer.querySelector('.site-credit')||document.querySelector('.site-credit');
  if(!note){
    note=document.createElement('p');
    footer.appendChild(note);
  }else if(note.parentElement!==footer){
    note.remove();
    footer.appendChild(note);
  }
  note.className='site-credit';
  note.textContent=phrase;

  if(!document.getElementById('site-footer-style')){
    const style=document.createElement('style');
    style.id='site-footer-style';
    style.textContent='.site-footer{max-width:1440px;margin:22px auto 0;padding:0 24px 16px}.site-credit{margin:0;padding:12px 8px 0;border-top:1px solid #e1e9e4;text-align:center;color:#89958e;font-size:.72rem;line-height:1.8;opacity:.82}.dark .site-credit{border-color:#34483e;color:#9aa8a0}@media(max-width:580px){.site-footer{padding:0 13px 14px}.site-credit{font-size:.68rem}}';
    document.head.appendChild(style);
  }
})();