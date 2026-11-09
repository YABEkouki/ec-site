const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
for(const file of ['orders/edit.html','orders/edit-confirm.html','orders/edit-result.html','orders/increase-payment.html','orders/increase-payment-return.html','checkout/payment.html','checkout/payment-return.html','checkout/complete.html']) {
 test(file+' uses the existing site layout with mobile viewport',()=>{
  const s=fs.readFileSync('src/main/resources/templates/'+file,'utf8');
  assert.match(s,/<html[^>]*lang="ja"/);assert.match(s,/name="viewport" content="width=device-width, initial-scale=1"/);
  assert.match(s,/fragments\/header :: header/);assert.match(s,/@\{\/css\/style.css\}/);assert.match(s,/<main\b[^>]*class="flow-main"/);
 });
}
